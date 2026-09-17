package com.mayaping.ai.langchain4j.rag.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线索引管线（父子分块）：
 *
 * 知识文档 → 父块切分(1000字) → 父块原文存入ES parents索引
 *                  ↓
 *            子块切分(300字) → 子块向量化 → 向量+文本+元数据存入ES children索引
 *
 * 设计要点：
 * - 检索用子块（小而精，向量/BM25召回率高），上下文用父块（大而全，供LLM完整语义）
 * - 子块元数据带parentId，检索命中后可回查父块原文
 * - DashScope embedding接口有批量上限，按10条一批向量化
 * - 先删后建索引，保证全量重建的幂等性
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final int PARENT_MAX_CHARS = 1000;
    private static final int PARENT_OVERLAP_CHARS = 100;
    private static final int CHILD_MAX_CHARS = 300;
    private static final int CHILD_OVERLAP_CHARS = 30;
    private static final int EMBED_BATCH_SIZE = 10;

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private final String knowledgeDir;
    private final String childrenIndex;
    private final String parentsIndex;

    public KnowledgeIngestionService(ElasticsearchClient elasticsearchClient,
                                     EmbeddingModel embeddingModel,
                                     EmbeddingStore<TextSegment> embeddingStore,
                                     @Value("${rag.knowledge-dir}") String knowledgeDir,
                                     @Value("${rag.index.children}") String childrenIndex,
                                     @Value("${rag.index.parents}") String parentsIndex) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.knowledgeDir = knowledgeDir;
        this.childrenIndex = childrenIndex;
        this.parentsIndex = parentsIndex;
    }

    /**
     * 全量重建知识库索引（幂等：先删旧索引再重建）
     */
    public IngestionStats ingestAll() throws IOException {
        Path dir = Path.of(knowledgeDir).toAbsolutePath();
        if (!Files.exists(dir)) {
            throw new IllegalStateException("知识库目录不存在：" + dir);
        }

        List<Document> documents = loadDocuments(dir);
        log.info("加载知识文档 {} 份，目录：{}", documents.size(), dir);

        recreateIndices(documents.size() > 0);

        IngestionStats stats = new IngestionStats();
        stats.add("documents", documents.size());

        DocumentByParagraphSplitter parentSplitter = new DocumentByParagraphSplitter(PARENT_MAX_CHARS, PARENT_OVERLAP_CHARS);
        DocumentByParagraphSplitter childSplitter = new DocumentByParagraphSplitter(CHILD_MAX_CHARS, CHILD_OVERLAP_CHARS);

        // 先索引父块，同时收集所有子块，最后批量向量化入children索引
        List<TextSegment> allChildren = new ArrayList<>();

        for (Document document : documents) {
            String docId = document.metadata().getString("file_name");
            List<TextSegment> parentSegments = parentSplitter.split(document);

            for (int i = 0; i < parentSegments.size(); i++) {
                TextSegment parentSegment = parentSegments.get(i);
                String parentId = docId + "#" + i;

                indexParent(parentId, parentSegment.text(), docId, document.metadata().getString("file_name"));

                // 父块切成子块，子块带上 parentId 元数据（父子关联的关键）
                List<TextSegment> children = childSplitter.split(
                        Document.from(parentSegment.text(), parentSegment.metadata()));
                for (TextSegment child : children) {
                    Map<String, Object> childMetadata = new HashMap<>(child.metadata().toMap());
                    childMetadata.put("parentId", parentId);
                    childMetadata.put("docId", docId);
                    allChildren.add(TextSegment.from(child.text(), dev.langchain4j.data.document.Metadata.from(childMetadata)));
                }
            }
            stats.add("parents", parentSegments.size());
        }

        embedAndStoreChildren(allChildren);
        stats.add("children", allChildren.size());

        // 立即刷新，保证写入后马上可检索
        refreshIndex(parentsIndex);
        refreshIndex(childrenIndex);

        log.info("索引完成：{}", stats.asMap());
        return stats;
    }

    /**
     * 递归加载目录下的文档：md/txt用文本解析器，pdf用pdfbox解析器
     */
    private List<Document> loadDocuments(Path dir) {
        DocumentParser textParser = new TextDocumentParser();
        DocumentParser pdfParser = new ApachePdfBoxDocumentParser();

        //注意：FileSystemDocumentLoader以"目录相对路径"做glob匹配，顶层文件相对路径无目录前缀，
        //因此pattern不能带前导"**/"（否则顶层文件全部漏掉），递归子目录由loadDocumentsRecursively负责
        PathMatcher textMatcher = dir.getFileSystem().getPathMatcher("glob:*.{txt,md}");
        PathMatcher pdfMatcher = dir.getFileSystem().getPathMatcher("glob:*.pdf");

        List<Document> documents = new ArrayList<>();
        documents.addAll(FileSystemDocumentLoader.loadDocumentsRecursively(dir, textMatcher, textParser));
        documents.addAll(FileSystemDocumentLoader.loadDocumentsRecursively(dir, pdfMatcher, pdfParser));
        return documents;
    }

    /**
     * 删除并重建索引。children索引由langchain4j在首次写入时自动创建（含kNN向量mapping），
     * parents索引这里显式创建mapping
     */
    private void recreateIndices(boolean createParents) throws IOException {
        deleteIndexIfExists(childrenIndex);
        deleteIndexIfExists(parentsIndex);

        if (createParents) {
            elasticsearchClient.indices().create(c -> c
                    .index(parentsIndex)
                    .mappings(m -> m
                            .properties("text", Property.of(p -> p.text(t -> t)))
                            .properties("docId", Property.of(p -> p.keyword(k -> k)))
                            .properties("source", Property.of(p -> p.keyword(k -> k)))));
        }
    }

    private void deleteIndexIfExists(String indexName) throws IOException {
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            elasticsearchClient.indices().delete(d -> d.index(indexName));
            log.info("已删除旧索引 {}", indexName);
        }
    }

    /**
     * 父块写入parents索引：id=parentId，供检索侧按id回查
     */
    private void indexParent(String parentId, String text, String docId, String source) throws IOException {
        Map<String, Object> parentDoc = new HashMap<>();
        parentDoc.put("text", text);
        parentDoc.put("docId", docId);
        parentDoc.put("source", source);

        elasticsearchClient.index(i -> i
                .index(parentsIndex)
                .id(parentId)
                .document(parentDoc));
    }

    /**
     * 子块批量向量化并写入children索引（DashScope批量接口有上限，按10条一批）
     */
    private void embedAndStoreChildren(List<TextSegment> children) {
        for (int from = 0; from < children.size(); from += EMBED_BATCH_SIZE) {
            List<TextSegment> batch = children.subList(from, Math.min(from + EMBED_BATCH_SIZE, children.size()));
            Response<List<Embedding>> response = embeddingModel.embedAll(batch);
            embeddingStore.addAll(response.content(), batch);
        }
    }

    private void refreshIndex(String indexName) throws IOException {
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            elasticsearchClient.indices().refresh(r -> r.index(indexName));
        }
    }
}
