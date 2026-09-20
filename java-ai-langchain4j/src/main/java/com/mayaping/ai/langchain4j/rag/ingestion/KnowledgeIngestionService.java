package com.mayaping.ai.langchain4j.rag.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.mayaping.ai.langchain4j.rag.ingestion.DocumentFingerprintStore.DocRecord;
import com.mayaping.ai.langchain4j.rag.ingestion.StructuredDocumentLoader.ContentElement;
import com.mayaping.ai.langchain4j.rag.ingestion.TableAwareSplitter.Block;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 离线索引管线（父子分块 + 增量更新 + 别名原子发布）。
 *
 * ## 两条输入路径（不变）
 *
 * **纯文本路径**（.md/.txt，含人工维护的文档）
 *   文档 → 父块切分(1000字) → 父块原文存入ES parents索引
 *                ↓
 *          子块切分(300字) → 子块向量化 → 向量+文本+元数据存入ES children索引
 *
 * **结构化路径**（MinerU产出的 &lt;name&gt;_content_list.json）
 *   由 {@link TableAwareSplitter} 按内容块类型切分：表格是原子单元、行级做子块、
 *   超长表格拆片时重复表头。这是PDF进知识库的**唯一推荐方式**。
 *
 * ## 相比改造前的三处变化
 *
 * **1. 增量更新（默认路径）**
 *   改造前只有全量重建，判断"要不要重建"的依据是人的记忆，于是只能每次都全量跑，
 *   代价是每个子块都重新调一次 embedding。现在由 {@link DocumentFingerprintStore}
 *   账本算出"哪些文档真变了"：未变的跳过，变了的重新切分，
 *   且**未变文档的向量通过 ES _reindex 直接搬运，不重新 embedding**。
 *   改一份文档的 embedding 成本从 O(全部子块) 降到 O(该份文档的子块)。
 *
 * **2. 别名原子发布（{@link IndexManager}）**
 *   改造前是"先删旧索引再重建"，中间窗口内检索命中空索引——用户问什么都是
 *   "知识库中未检索到相关信息"，而日志一切正常。现在写到新的物理索引上，
 *   写完再原子切换别名，**重建期间检索始终可用**，失败也不会污染线上。
 *
 * **3. 并发保护**
 *   改造前两个并发的 POST /api/ingest 会互相删索引（A删了B正在写的库），
 *   结果是索引残缺且不报错。现在用 tryLock 串行化，第二个请求直接得到明确错误。
 *
 * ## 保留的关键设计
 * - 检索用子块（小而精），上下文用父块（大而全）
 * - 子块元数据带parentId，检索命中后回查父块原文
 * - 元数据补齐 docId/source/page/contentType/section，是引用溯源的依据
 * - DashScope embedding有批量上限，按10条一批向量化
 * - 全程收集校验告警（见 {@link IngestionStats#warn}），把静默失效变成显式失败
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final int PARENT_MAX_CHARS = 1000;
    private static final int PARENT_OVERLAP_CHARS = 100;
    private static final int CHILD_MAX_CHARS = 300;
    private static final int CHILD_OVERLAP_CHARS = 30;
    private static final int EMBED_BATCH_SIZE = 10;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final TableAwareSplitter tableAwareSplitter;
    private final IndexManager indexManager;
    private final DocumentFingerprintStore fingerprintStore;

    private final String knowledgeDir;
    private final String childrenAlias;
    private final String parentsAlias;
    private final String documentsIndex;
    private final int minCharsPerPage;
    private final boolean incrementalEnabled;
    private final boolean keepOldIndices;
    private final boolean pruneDeleted;

    /**
     * 摄入互斥锁。
     *
     * 为什么必须串行：每次发布都会创建新物理索引并切换别名，两个并发发布会
     * 各自创建一套索引、后切换的那个把先切换的挤掉——索引整体回退，且没有任何报错。
     *
     * 用 tryLock 而非 lock：第二个请求拿不到锁时**立即返回**而不是排队等待，
     * 因为摄入是运维手工触发的长任务，让它排队没有意义（用户早就关页面了），
     * 明确告知"已有一次摄入在进行中"比静默等待几分钟更有用。
     */
    private final ReentrantLock ingestionLock = new ReentrantLock();

    /**
     * 按索引名缓存的写入用 EmbeddingStore。
     * 索引名含时间戳，所以不会跨发布复用，无需考虑失效清理。
     */
    private final Map<String, EmbeddingStore<TextSegment>> storeCache = new ConcurrentHashMap<>();

    public KnowledgeIngestionService(ElasticsearchClient elasticsearchClient,
                                     EmbeddingModel embeddingModel,
                                     EmbeddingStore<TextSegment> embeddingStore,
                                     TableAwareSplitter tableAwareSplitter,
                                     IndexManager indexManager,
                                     @Value("${rag.knowledge-dir}") String knowledgeDir,
                                     @Value("${rag.index.children}") String childrenAlias,
                                     @Value("${rag.index.parents}") String parentsAlias,
                                     @Value("${rag.index.documents}") String documentsIndex,
                                     @Value("${rag.ingestion.min-chars-per-page:50}") int minCharsPerPage,
                                     @Value("${rag.ingestion.max-table-rows-per-child:200}") int maxTableRowsPerChild,
                                     @Value("${rag.ingestion.incremental:true}") boolean incrementalEnabled,
                                     @Value("${rag.ingestion.keep-old-indices:true}") boolean keepOldIndices,
                                     @Value("${rag.ingestion.prune-deleted:true}") boolean pruneDeleted) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.tableAwareSplitter = tableAwareSplitter;
        this.indexManager = indexManager;
        this.knowledgeDir = knowledgeDir;
        this.childrenAlias = childrenAlias;
        this.parentsAlias = parentsAlias;
        this.documentsIndex = documentsIndex;
        this.minCharsPerPage = minCharsPerPage;
        this.incrementalEnabled = incrementalEnabled;
        this.keepOldIndices = keepOldIndices;
        this.pruneDeleted = pruneDeleted;

        this.fingerprintStore = new DocumentFingerprintStore(elasticsearchClient, documentsIndex,
                DocumentFingerprintStore.configFingerprint(
                        PARENT_MAX_CHARS, PARENT_OVERLAP_CHARS,
                        CHILD_MAX_CHARS, CHILD_OVERLAP_CHARS, maxTableRowsPerChild));
    }

    // ==================== 对外入口 ====================

    /**
     * 增量摄入（默认入口）：只处理变化的文档。
     *
     * 三种变更各自的处理：
     *   - 新增/修改 → 重新切分灌入
     *   - 未变化   → 向量从旧索引搬运，不重新 embedding
     *   - 文件已删 → 定向清除其索引（由 pruneDeleted 控制）
     */
    public IngestionStats ingest() throws IOException {
        return ingest(false);
    }

    /**
     * @param forceFullRebuild true则忽略账本，所有文档重新切分灌入。
     *                         注意：切分配置变更时**不需要**手工传true——
     *                         {@link DocumentFingerprintStore} 的配置指纹会自动触发全量
     */
    public IngestionStats ingest(boolean forceFullRebuild) throws IOException {
        if (!ingestionLock.tryLock()) {
            throw new IllegalStateException(
                    "已有一次索引摄入正在进行中，请等待其完成后再试（摄入是长任务，不支持并发）");
        }
        try {
            Path dir = Path.of(knowledgeDir).toAbsolutePath();
            if (!Files.exists(dir)) {
                throw new IllegalStateException("知识库目录不存在：" + dir);
            }

            IngestionStats stats = new IngestionStats();
            stats.label("mode", (forceFullRebuild || !incrementalEnabled) ? "full" : "incremental");

            // ===== 第一步：盘点磁盘上的文档，算出各自的内容指纹 =====
            List<Path> structuredJsons = findStructuredJsons(dir);
            Set<String> structuredDocIds = structuredJsons.stream()
                    .map(StructuredDocumentLoader::docIdOf)
                    .collect(Collectors.toSet());

            List<Document> textDocuments = loadTextDocuments(dir, structuredDocIds, stats);
            warnAboutStrayPdfs(dir, stats);

            // ===== 第二步：与账本比对，决定哪些要重灌、哪些要清除 =====
            Map<String, DocRecord> ledger = fingerprintStore.loadAll();
            Map<String, String> currentHashes = new LinkedHashMap<>();
            Map<String, Document> changedTextDocs = new LinkedHashMap<>();
            List<Path> changedStructuredJsons = new ArrayList<>();

            boolean forceAll = forceFullRebuild || !incrementalEnabled || ledger.isEmpty();
            if (ledger.isEmpty() && !forceFullRebuild) {
                log.info("指纹账本为空，本次按首次全量索引处理");
            }

            for (Document document : textDocuments) {
                String docId = document.metadata().getString("file_name");
                String hash = DocumentFingerprintStore.hashOf(document.text());
                currentHashes.put(docId, hash);
                if (forceAll || fingerprintStore.needsReindex(ledger.get(docId), hash)) {
                    changedTextDocs.put(docId, document);
                }
            }
            for (Path jsonPath : structuredJsons) {
                String docId = StructuredDocumentLoader.docIdOf(jsonPath);
                // 结构化路径比对的是原始json内容：json是切分的直接输入，
                // 它变了（MinerU重跑/换模式）就必须重灌
                String hash = DocumentFingerprintStore.hashOf(Files.readString(jsonPath, StandardCharsets.UTF_8));
                currentHashes.put(docId, hash);
                if (forceAll || fingerprintStore.needsReindex(ledger.get(docId), hash)) {
                    changedStructuredJsons.add(jsonPath);
                }
            }

            // 文件已从磁盘消失的文档（账本里有、本次没扫到）
            List<String> removedDocIds = pruneDeleted
                    ? ledger.keySet().stream().filter(id -> !currentHashes.containsKey(id)).sorted().toList()
                    : List.of();

            int changed = changedTextDocs.size() + changedStructuredJsons.size();
            int unchanged = Math.max(currentHashes.size() - changed, 0);
            stats.put("documentsTotal", currentHashes.size());
            stats.put("documentsChanged", changed);
            stats.put("documentsSkipped", unchanged);
            stats.put("documentsRemoved", removedDocIds.size());

            log.info("摄入盘点：共{}份文档，需重灌{}份，跳过{}份，需清除{}份（目录：{}）",
                    currentHashes.size(), changed, unchanged, removedDocIds.size(), dir);

            if (changed == 0 && removedDocIds.isEmpty()) {
                log.info("所有文档均未变化，无需重建索引");
                stats.put("published", 0);
                return stats;
            }

            // ===== 第三步：写新索引 + 原子发布 =====
            publish(changedTextDocs, changedStructuredJsons, removedDocIds, currentHashes,
                    forceAll ? null : ledger.keySet(), stats);

            validate(stats);
            log.info("索引完成：{}，告警 {} 条", stats.asMap(), stats.warnings().size());
            stats.warnings().forEach(warning -> log.warn("摄入校验：{}", warning));
            return stats;
        } finally {
            ingestionLock.unlock();
        }
    }

    /**
     * 全量重建：保留原 {@code ingestAll()} 的语义（所有文档重新切分灌入），
     * 但仍然走别名原子发布，因此**不再有检索不可用窗口**。
     *
     * 这是与改造前最重要的行为差异：调用方不再需要为了"安全重建"而在低峰期留维护窗口。
     */
    public IngestionStats ingestAll() throws IOException {
        return ingest(true);
    }

    // ==================== 发布流程 ====================

    /**
     * 一次完整的索引发布。
     *
     * 关键点：**增量时也创建全新的物理索引**，而不是原地修改旧索引。这是刻意的取舍：
     *
     *   - 原地修改（只删改动的docId再写）：省事，但**丧失原子性**。
     *     写到一半失败，索引就是"一半新一半旧"的状态，没有回滚点，
     *     而且这种残缺索引不会报错，只会表现为"某些问题时对时错"。
     *   - 新索引 + 别名切换：代价是每次发布都要把未变文档的块搬一遍，
     *     但换来**任何失败都能整体回滚**（别名始终指向旧的完整索引）。
     *
     * 搬运用的是 ES 内部 _reindex，不产生 embedding 调用。
     * embedding 才是这条管线里的真金白银开销，搬运只是磁盘操作。
     */
    private void publish(Map<String, Document> changedTextDocs,
                         List<Path> changedStructuredJsons,
                         List<String> removedDocIds,
                         Map<String, String> currentHashes,
                         Set<String> ledgerDocIds,
                         IngestionStats stats) throws IOException {

        String previousChildren = indexManager.currentPhysicalIndex(childrenAlias);
        String previousParents = indexManager.currentPhysicalIndex(parentsAlias);

        Map<String, Property> parentMappings = new LinkedHashMap<>();
        parentMappings.put("text", Property.of(p -> p.text(t -> t)));
        parentMappings.put("docId", Property.of(p -> p.keyword(k -> k)));
        parentMappings.put("source", Property.of(p -> p.keyword(k -> k)));
        parentMappings.put("contentType", Property.of(p -> p.keyword(k -> k)));
        parentMappings.put("section", Property.of(p -> p.text(t -> t)));
        parentMappings.put("page", Property.of(p -> p.integer(n -> n)));

        // children 索引的 kNN 向量 mapping 由 langchain4j 在首次写入时自动创建，
        // 这里传 null 表示交给框架建；parents/documents 需要显式 mapping
        try (IndexManager.IndexPublication childrenPub = indexManager.beginPublication(childrenAlias, null);
             IndexManager.IndexPublication parentsPub = indexManager.beginPublication(parentsAlias, parentMappings)) {

            // 未变文档的块从上一次发布搬运过来。旧索引不存在（首次索引）时返回，不搬运
            carryOverUnchanged(previousChildren, previousParents, childrenPub.physicalName(),
                    parentsPub.physicalName(), changedTextDocs.keySet(), changedStructuredJsons,
                    removedDocIds, stats);

            for (Document document : changedTextDocs.values()) {
                ingestProseDocument(document, childrenPub.physicalName(), parentsPub.physicalName(), stats);
            }
            for (Path jsonPath : changedStructuredJsons) {
                ingestStructuredDocument(jsonPath, childrenPub.physicalName(), parentsPub.physicalName(), stats);
            }

            indexManager.publish(childrenPub);
            indexManager.publish(parentsPub);

            // 账本更新必须在发布之后：发布失败时账本不该被推进，
            // 否则下次增量会误以为"这批已经索引过了"而跳过，索引就永久缺了这批文档
            updateLedger(currentHashes, removedDocIds, stats);

            stats.put("published", 1);
        }

        cleanupOldIndices(previousChildren, previousParents, stats);
    }

    /**
     * 把未变文档在旧索引里的块搬运到新索引。
     *
     * 这一层是增量真正的价值所在：搬运是 ES 内部的磁盘操作（_reindex），
     * **不产生 embedding API 调用**。而 embedding 是这个管线里唯一的真金白银开销，
     * 也是重建耗时的绝对大头。
     *
     * 前提：旧索引必须存在且 schema 兼容。首次索引（previous=null）时直接跳过。
     */
    private void carryOverUnchanged(String previousChildren,
                                    String previousParents,
                                    String targetChildren,
                                    String targetParents,
                                    Set<String> changedDocIds,
                                    List<Path> changedStructuredJsons,
                                    List<String> removedDocIds,
                                    IngestionStats stats) throws IOException {

        if (previousChildren == null || previousParents == null) {
            log.debug("上一次发布不存在物理索引，本次为首次索引，无需搬运");
            return;
        }

        // 需要排除的docId = 已变更的 + 已删除的
        Set<String> excluded = new LinkedHashSet<>(changedDocIds);
        changedStructuredJsons.forEach(p -> excluded.add(StructuredDocumentLoader.docIdOf(p)));
        excluded.addAll(removedDocIds);

        long carriedChildren = carryOverOne(previousChildren, targetChildren, excluded, "children", stats);
        long carriedParents = carryOverOne(previousParents, targetParents, excluded, "parents", stats);
        if (carriedChildren > 0) {
            log.info("增量复用：{} 个父块、{} 个子块从上一版索引搬运（免重新切分与向量化）",
                    carriedParents, carriedChildren);
        }
    }

    private long carryOverOne(String sourceIndex, String targetIndex, Set<String> excludedDocIds,
                              String kind, IngestionStats stats) throws IOException {
        boolean sourceExists = elasticsearchClient.indices().exists(e -> e.index(sourceIndex)).value();
        if (!sourceExists) {
            log.warn("上一版索引 {} 不存在，{} 无法复用（将按新增处理）", sourceIndex, kind);
            return 0;
        }

        // 用 bool.must_not + terms 排除变更文档。
        //
        // 字段路径不能写死，必须从索引的**实际 mapping** 里探测——原因见
        // IndexManager.resolveKeywordField 的注释：children 索引由 ES 动态映射创建，
        // 字段落在 metadata 下还是顶层、被推断成 text 还是 keyword，都不是我们决定的。
        // 猜错的后果不是报错，而是 terms 匹配不到任何文档 → 排除形同虚设 →
        // 旧数据被整份搬运并与新灌文档叠加成重复子块（子块数翻倍、检索命中重复内容）。
        String docIdField = indexManager.resolveKeywordField(sourceIndex, "docId");

        Query query;
        if (excludedDocIds.isEmpty() || docIdField == null) {
            // 没有要排除的文档，或字段探测失败（索引还没数据）。
            // 探测失败时只能整份搬运：宁可留下重复子块（可见、可修复），
            // 也不能因为"拿不准"而跳过搬运——那会让未变文档从索引里凭空消失，
            // 且账本认为它们没变，后续增量也不会补，属于不可见的永久缺失
            query = Query.of(q -> q.matchAll(m -> m));
            if (docIdField == null && !excludedDocIds.isEmpty()) {
                stats.warn(String.format(
                        "%s 索引中未探测到 docId 字段（索引 %s），本次搬运未排除已变更文档，"
                                + "新索引可能出现重复子块。确认新索引内容后可删除旧索引；"
                                + "若重复严重请用 force=true 全量重建",
                        kind, sourceIndex));
            }
        } else {
            String field = docIdField;
            query = Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.terms(t -> t
                    .field(field)
                    .terms(tt -> tt.value(excludedDocIds.stream()
                            .map(FieldValue::of)
                            .toList()))))));
        }

        var response = elasticsearchClient.reindex(r -> r
                .source(s -> s.index(sourceIndex).query(query))
                .dest(d -> d.index(targetIndex))
                .waitForCompletion(true));

        if (!response.failures().isEmpty()) {
            // 搬运失败不能静默：否则未变文档会从新索引里凭空消失，
            // 而账本仍认为"它们没变" → 下次增量也不会补，索引永久缺内容
            stats.warn(String.format(
                    "%s 搬运有 %d 条失败（上一版索引 %s → 新索引 %s），"
                            + "受影响的文档可能在新索引中缺失，建议手工执行一次全量重建（force=true）",
                    kind, response.failures().size(), sourceIndex, targetIndex));
            response.failures().forEach(f -> log.warn("搬运失败：index={} id={} status={} cause={}",
                    f.index(), f.id(), f.status(), f.cause()));
        }

        long copied = response.total();
        stats.add(kind.equals("children") ? "childrenCarriedOver" : "parentsCarriedOver", (int) copied);
        return copied;
    }
    private void updateLedger(Map<String, String> currentHashes,
                              List<String> removedDocIds,
                              IngestionStats stats) throws IOException {
        String now = LocalDateTime.now().format(STAMP);
        for (Map.Entry<String, String> entry : currentHashes.entrySet()) {
            fingerprintStore.record(entry.getKey(), entry.getValue(),
                    stats.docParentCount(entry.getKey()), stats.docChildCount(entry.getKey()), now);
        }
        for (String removed : removedDocIds) {
            fingerprintStore.forget(removed);
        }
        refreshIndex(documentsIndex);
    }

    private void cleanupOldIndices(String previousChildren, String previousParents,
                                   IngestionStats stats) throws IOException {
        int oldCount = (previousChildren != null ? 1 : 0) + (previousParents != null ? 1 : 0);
        if (keepOldIndices) {
            stats.put("oldIndicesKept", oldCount);
            if (previousChildren != null) {
                log.info("保留旧物理索引 {} / {}（rag.ingestion.keep-old-indices=true，确认新索引可检索后可手工删除）",
                        previousChildren, previousParents);
            }
            return;
        }
        if (previousChildren != null) {
            indexManager.deleteIndex(previousChildren);
        }
        if (previousParents != null) {
            indexManager.deleteIndex(previousParents);
        }
        stats.put("oldIndicesKept", 0);
    }

    // ==================== 文档发现 ====================

    private List<Path> findStructuredJsons(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(StructuredDocumentLoader::looksLikeStructuredContent)
                    .sorted()
                    .toList();
        }
    }

    /**
     * 加载纯文本文档。
     *
     * 两处刻意的排除，都是为了避开"双份摄入"这个隐蔽故障——同一份文档以两种形态
     * 进库时，检索会时对时错，且极难排查：
     *
     * 1. **不收 .pdf**。PDF 必须经 tools/pdf2md.py（MinerU）转成 md + content_list.json
     *    再进来。若这里也收 PDF，就会同时摄入 MinerU 转出的好 md 和 PDFBox 抽的垃圾
     *    （表格列错位）。目录里残留的 PDF 会被显式告警，而不是默默跳过。
     * 2. **跳过已有 content_list.json 的兄弟 md**。MinerU 会同时产出 .md 和
     *    _content_list.json，两者内容相同但结构不同，都入库就是重复。
     */
    private List<Document> loadTextDocuments(Path dir, Set<String> structuredDocIds, IngestionStats stats)
            throws IOException {

        //注意：FileSystemDocumentLoader以"目录相对路径"做glob匹配，顶层文件相对路径无目录前缀，
        //因此pattern不能带前导"**/"（否则顶层文件全部漏掉），递归子目录由loadDocumentsRecursively负责
        PathMatcher textMatcher = dir.getFileSystem().getPathMatcher("glob:*.{txt,md}");
        DocumentParser textParser = new TextDocumentParser();

        return FileSystemDocumentLoader.loadDocumentsRecursively(dir, textMatcher, textParser).stream()
                .filter(document -> {
                    String fileName = document.metadata().getString("file_name");
                    if (fileName != null && structuredDocIds.contains(fileName)) {
                        log.info("{} 已存在结构化版本(content_list.json)，跳过纯文本版本以避免重复摄入", fileName);
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    private void warnAboutStrayPdfs(Path dir, IngestionStats stats) throws IOException {
        List<String> strayPdfs;
        try (Stream<Path> walk = Files.walk(dir)) {
            strayPdfs = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .map(path -> dir.relativize(path).toString())
                    .sorted()
                    .toList();
        }
        if (!strayPdfs.isEmpty()) {
            stats.warn(String.format(
                    "知识库目录下发现 %d 个PDF，已全部跳过：%s。"
                            + "PDF请先用 tools/pdf2md.py（MinerU，-m auto）转换成 md + content_list.json 再入库，"
                            + "直接入库会与转换产物重复摄入，且PDFBox对表格无能为力（会静默产出错位内容）",
                    strayPdfs.size(), strayPdfs));
        }
    }

    // ==================== 纯文本路径 ====================

    private void ingestProseDocument(Document document, String childrenIndex, String parentsIndex,
                                     IngestionStats stats) throws IOException {
        String docId = document.metadata().getString("file_name");
        String source = docId;

        DocumentByParagraphSplitter parentSplitter =
                new DocumentByParagraphSplitter(PARENT_MAX_CHARS, PARENT_OVERLAP_CHARS);
        DocumentByParagraphSplitter childSplitter =
                new DocumentByParagraphSplitter(CHILD_MAX_CHARS, CHILD_OVERLAP_CHARS);

        List<TextSegment> parentSegments = parentSplitter.split(document);

        for (int i = 0; i < parentSegments.size(); i++) {
            String parentId = docId + "#" + i;
            TextSegment parent = parentSegments.get(i);

            // 纯文本路径拿不到页码（不编造），但source/docId/contentType仍要补齐
            Map<String, Object> metadata = baseMetadata(docId, source, null, "prose");

            indexParent(parentsIndex, parentId, parent.text(), metadata);
            indexChildren(childrenIndex, parentId, childSplitter.split(
                    Document.from(parent.text(), toMetadata(metadata))), metadata, stats, docId);
            stats.addDocParents(docId, 1);
        }

        stats.add("documents", 1);
        stats.add("parents", parentSegments.size());
    }

    // ==================== 结构化路径（MinerU产物）====================

    private void ingestStructuredDocument(Path jsonPath, String childrenIndex, String parentsIndex,
                                          IngestionStats stats) throws IOException {
        String docId = StructuredDocumentLoader.docIdOf(jsonPath);
        String source = docId;

        List<ContentElement> elements = StructuredDocumentLoader.load(jsonPath);
        stats.add("documents", 1);

        if (elements.isEmpty()) {
            stats.warn(String.format("[%s] content_list.json 解析出0个内容块，转换可能失败", docId));
            return;
        }

        checkTableLoss(jsonPath, docId, elements, stats);
        checkCharsPerPage(docId, elements, stats);

        List<String> splitterWarnings = new ArrayList<>();
        List<Block> blocks = tableAwareSplitter.split(elements, docId, source, splitterWarnings);
        splitterWarnings.forEach(stats::warn);

        int parents = 0;
        int tableBlocks = 0;

        for (Block block : blocks) {
            String parentId = docId + "#" + parents;
            Map<String, Object> metadata = block.metadata();

            indexParent(parentsIndex, parentId, block.parentText(), metadata);
            indexChildren(childrenIndex, parentId, toSegments(block.childTexts(), metadata), metadata, stats, docId);
            stats.addDocParents(docId, 1);

            parents++;
            if ("table".equals(metadata.get("contentType"))) {
                tableBlocks++;
            }
        }

        stats.add("parents", parents);
        stats.add("tableBlocks", tableBlocks);
    }

    /**
     * "表格丢失"检测：Markdown里有表格，但content_list.json里没有table块。
     * 两者出自同一次解析，出现这种不一致基本可以断定表格在解析阶段被丢了——
     * 最常见的原因是 MinerU 用了 {@code -m txt} 模式，该模式明确不处理图片和表格，
     * 且**不报错**。
     */
    private void checkTableLoss(Path jsonPath, String docId, List<ContentElement> elements,
                                IngestionStats stats) {
        boolean hasTableElement = elements.stream().anyMatch(ContentElement::isTable);
        if (hasTableElement) {
            return;
        }

        Path siblingMd = jsonPath.resolveSibling(docId);
        if (!Files.exists(siblingMd)) {
            return;
        }

        try {
            String markdown = Files.readString(siblingMd, StandardCharsets.UTF_8);
            if (markdown.contains("|---") || markdown.contains("| ---")) {
                stats.warn(String.format(
                        "[%s] Markdown中存在表格，但content_list.json里没有任何table块——"
                                + "表格很可能在解析阶段丢失。请确认MinerU未使用 -m txt（该模式不处理表格且不报错）",
                        docId));
            }
        } catch (IOException e) {
            log.debug("读取 {} 用于表格一致性校验失败：{}", siblingMd, e.getMessage());
        }
    }

    /**
     * 字符密度校验：扫描件没走OCR（或OCR失败）时，整页几乎抽不出文字，
     * 表现为"文档入库了但检索永远查不到"。
     */
    private void checkCharsPerPage(String docId, List<ContentElement> elements, IngestionStats stats) {
        int maxPage = elements.stream()
                .map(ContentElement::page)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (maxPage <= 0) {
            return;
        }

        int totalChars = elements.stream()
                .map(ContentElement::text)
                .filter(Objects::nonNull)
                .mapToInt(String::length)
                .sum();

        if (totalChars / maxPage < minCharsPerPage) {
            stats.warn(String.format(
                    "[%s] 共%d页但仅抽取到%d字符（每页不足%d字），疑似扫描件未被OCR处理，"
                            + "该文档大概率无法被检索到。请用 MinerU 的 -m ocr（或 auto）模式重新转换",
                    docId, maxPage, totalChars, minCharsPerPage));
        }
    }

    // ==================== 索引写入 ====================

    private Map<String, Object> baseMetadata(String docId, String source, Integer page, String contentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("docId", docId);
        metadata.put("source", source);
        metadata.put("contentType", contentType);
        if (page != null) {
            metadata.put("page", page);
        }
        return metadata;
    }

    private List<TextSegment> toSegments(List<String> texts, Map<String, Object> metadata) {
        return texts.stream()
                .map(text -> TextSegment.from(text, toMetadata(metadata)))
                .toList();
    }

    /**
     * 父块写入parents索引：id=parentId，供检索侧按id回查
     */
    private void indexParent(String parentsIndex, String parentId, String text,
                             Map<String, Object> metadata) throws IOException {
        Map<String, Object> parentDoc = new HashMap<>(metadata);
        parentDoc.put("text", text);

        elasticsearchClient.index(i -> i
                .index(parentsIndex)
                .id(parentId)
                .document(parentDoc));
    }

    /**
     * 子块写入children索引：补上parentId（父子关联的关键）后批量向量化
     */
    private int indexChildren(String childrenIndex, String parentId, List<TextSegment> children,
                              Map<String, Object> parentMetadata, IngestionStats stats, String docId) {
        if (children.isEmpty()) {
            return 0;
        }

        List<TextSegment> segments = new ArrayList<>(children.size());
        for (TextSegment child : children) {
            Map<String, Object> metadata = new HashMap<>(parentMetadata);
            metadata.put("parentId", parentId);
            segments.add(TextSegment.from(child.text(), Metadata.from(metadata)));
        }

        embedAndStoreChildren(childrenIndex, segments);
        stats.add("children", segments.size());
        stats.addDocChildren(docId, segments.size());
        return segments.size();
    }

    /**
     * 子块批量向量化并写入children索引（DashScope批量接口有上限，按10条一批）。
     *
     * 落点是**新物理索引名**，不是构造时注入的 embeddingStore（它绑定在别名上）。
     * 原因：发布期间别名仍指向旧索引，往别名写会污染线上正在服务的索引——
     * 一旦发布失败回滚，旧索引里已经混进了半成品数据，且没有任何报错。
     *
     * ElasticsearchEmbeddingStore 是"一个实例绑定一个索引"的设计，没有"按索引名写入"
     * 的重载，所以这里按目标索引名建一个临时store。建store本身是轻量的（只是持有
     * client与索引名），真正的开销在 embedding 调用上，这里不重复付。
     */
    private void embedAndStoreChildren(String childrenIndex, List<TextSegment> children) {
        EmbeddingStore<TextSegment> targetStore = storeFor(childrenIndex);
        for (int from = 0; from < children.size(); from += EMBED_BATCH_SIZE) {
            List<TextSegment> batch = children.subList(from, Math.min(from + EMBED_BATCH_SIZE, children.size()));
            Response<List<Embedding>> response = embeddingModel.embedAll(batch);
            targetStore.addAll(response.content(), batch);
        }
    }

    /**
     * 按索引名取（或创建）EmbeddingStore。
     *
     * 带缓存：一次发布里 children 会被反复写入（每份文档、每个父块一次），
     * 每次都新建 store 虽然轻量但没必要。缓存以索引名为key，
     * 索引名含时间戳所以不会跨发布复用——这也正好避免了"旧store指向已删除索引"的问题。
     */
    private EmbeddingStore<TextSegment> storeFor(String indexName) {
        return storeCache.computeIfAbsent(indexName, name -> ElasticsearchEmbeddingStore.builder()
                .client(elasticsearchClient)
                .indexName(name)
                .build());
    }

    // ==================== 索引维护与校验 ====================

    private void refreshIndex(String indexName) throws IOException {
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            elasticsearchClient.indices().refresh(r -> r.index(indexName));
        }
    }

    /**
     * 全局校验：索引整体为空是最严重的情况——通常是目录路径配错、或所有文档都解析失败，
     * 但接口会正常返回，检索则永远无结果。
     */
    private void validate(IngestionStats stats) {
        if (stats.get("documentsTotal") == 0) {
            stats.warn("知识库目录下没有找到任何文档（.md/.txt/_content_list.json），索引为空");
            return;
        }
        int parents = stats.get("parents") + stats.get("parentsCarriedOver");
        int children = stats.get("children") + stats.get("childrenCarriedOver");
        if (parents == 0) {
            stats.warn("本次没有产出任何父块，且未从上一版索引复用，索引可能为空，请检查文档内容与切分配置");
        }
        if (children == 0 && parents > 0) {
            stats.warn("有父块但没有任何子块，检索将永远无法命中，请检查切分配置");
        }
    }

    private Metadata toMetadata(Map<String, Object> raw) {
        Metadata metadata = new Metadata();
        metadata.putAll(raw);
        return metadata;
    }
}
