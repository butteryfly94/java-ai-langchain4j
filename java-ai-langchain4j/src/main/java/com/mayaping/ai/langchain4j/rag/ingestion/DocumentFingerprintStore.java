package com.mayaping.ai.langchain4j.rag.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档指纹账本：记录"上一次索引时，每份文档长什么样"。
 *
 * ## 它解决什么问题
 *
 * 没有增量索引时，判断"索引是否需要重建"的唯一依据是人的记忆（"我改了哪个文件来着"）。
 * 于是运维只能每次都全量重建，代价是：
 *   - 每份文档的每个子块都要重新调一次 embedding（真金白银）
 *   - 重建窗口随文档数线性增长（可用性）
 *
 * 有了账本，"哪些文档变了"从一个**需要人记忆的问题**变成一个**可以计算的问题**：
 *
 *   对每份文档算内容hash → 与账本里的旧hash比对
 *     相同 → 跳过（不切分、不embedding、不写ES）
 *     不同 → 定向删除该docId的旧块，重灌
 *     账本里有、本次目录里没有 → 文件被删了，定向清除它的索引
 *
 * ## 为什么hash算的是"最终入索引的文本"而不是文件字节
 *
 * 这个选择决定了增量的正确性。如果 hash 算的是**文件原始字节**，那么：
 *   - 只在文件末尾加个空格、改个标点 → hash 变了 → 白白重灌一遍
 *   - 但反过来说，**切分逻辑改了**（比如 PARENT_MAX_CHARS 从 1000 调到 1200）
 *     而文件没动 → hash 没变 → 增量会跳过它 → 索引里还是旧切分的结果
 *
 * 后者是致命的静默错误：你改了切分参数、跑了增量、以为生效了，实际没有。
 * 所以账本额外记录 **[切分配置指纹]**（见 {@link #configFingerprint}）：
 * 一旦切分参数变化，所有文档的指纹都被视为失效，强制全量重建。
 * 这条规则让"配置变更"和"内容变更"共用同一套判定逻辑，不需要人记得"改配置要全量"。
 */
public class DocumentFingerprintStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentFingerprintStore.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final String configFingerprint;

    public DocumentFingerprintStore(ElasticsearchClient client, String indexName, String configFingerprint) {
        this.client = client;
        this.indexName = indexName;
        this.configFingerprint = configFingerprint;
    }

    /**
     * 账本里一条记录。
     *
     * {@code configFingerprint} 与 {@code contentHash} 必须都存在且匹配才算"未变更"——
     * 只有 contentHash 相同但 configFingerprint 不同，说明切分参数变了，必须重灌。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DocRecord {
        private String docId;
        private String contentHash;
        private String configFingerprint;
        private Integer parentCount;
        private Integer childCount;
        private String indexedAt;

        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getContentHash() { return contentHash; }
        public void setContentHash(String contentHash) { this.contentHash = contentHash; }
        public String getConfigFingerprint() { return configFingerprint; }
        public void setConfigFingerprint(String configFingerprint) { this.configFingerprint = configFingerprint; }
        public Integer getParentCount() { return parentCount; }
        public void setParentCount(Integer parentCount) { this.parentCount = parentCount; }
        public Integer getChildCount() { return childCount; }
        public void setChildCount(Integer childCount) { this.childCount = childCount; }
        public String getIndexedAt() { return indexedAt; }
        public void setIndexedAt(String indexedAt) { this.indexedAt = indexedAt; }
    }

    /**
     * 读取全部已有指纹。账本索引不存在时返回空map（全新环境，等价于"全部都是新增"）。
     */
    public Map<String, DocRecord> loadAll() throws IOException {
        Map<String, DocRecord> records = new HashMap<>();
        try {
            client.indices().refresh(r -> r.index(indexName));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.info("指纹账本 {} 尚不存在，本次视为首次全量索引", indexName);
                return records;
            }
            throw e;
        }

        try {
            var response = client.search(s -> s
                    .index(indexName)
                    .size(10000)
                    .query(q -> q.matchAll(m -> m)), DocRecord.class);

            response.hits().hits().forEach(hit -> {
                DocRecord record = hit.source();
                if (record != null && record.getDocId() != null) {
                    records.put(record.getDocId(), record);
                }
            });
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return records;
            }
            throw e;
        }

        log.info("指纹账本加载完成，共 {} 份文档记录", records.size());
        return records;
    }

    /**
     * 判定一份文档是否需要重新索引。
     *
     * @param existing 账本里的旧记录，可能为null（该文档从未索引过）
     * @param newHash  本次算出的内容hash
     * @return 需要重灌返回true
     */
    public boolean needsReindex(DocRecord existing, String newHash) {
        if (existing == null) {
            return true;
        }
        if (!configFingerprint.equals(existing.getConfigFingerprint())) {
            // 切分配置变了，文档内容没变也必须重灌，否则索引里还是旧切分的结果
            log.info("[{}] 切分配置指纹变化（{} → {}），强制重新索引",
                    existing.getDocId(), existing.getConfigFingerprint(), configFingerprint);
            return true;
        }
        return !newHash.equals(existing.getContentHash());
    }

    public void record(String docId, String contentHash, int parentCount, int childCount, String indexedAt)
            throws IOException {
        DocRecord record = new DocRecord();
        record.setDocId(docId);
        record.setContentHash(contentHash);
        record.setConfigFingerprint(configFingerprint);
        record.setParentCount(parentCount);
        record.setChildCount(childCount);
        record.setIndexedAt(indexedAt);

        client.index(i -> i.index(indexName).id(docId).document(record));
    }

    /** 文档已从磁盘删除时，同步移除账本记录 */
    public void forget(String docId) throws IOException {
        try {
            client.delete(d -> d.index(indexName).id(docId));
        } catch (ElasticsearchException e) {
            log.debug("移除指纹记录 {} 失败（可能本就不存在）：{}", docId, e.getMessage());
        }
    }

    /**
     * 计算文档内容的SHA-256。
     *
     * 用 hash 而不是 mtime：mtime 在 git checkout / 打包解压 / 跨机器同步后会整体变化，
     * 触发一次毫无意义的全量重灌；而内容hash只在内容真的变了时才变。
     * 代价是每次都要读一遍文件（磁盘IO），但相比一次 embedding API 调用可以忽略。
     */
    public static String hashOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是JDK必备算法，走到这里说明环境损坏，不该降级为"每次都重灌"
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }

    /**
     * 切分配置指纹：把影响切分结果的所有参数拼成一个字符串再做hash。
     *
     * 任何影响"文档 → 块"映射的参数都必须进这里。漏掉一个，就会出现
     * "改了参数但增量跳过了重灌"的静默错误。
     */
    public static String configFingerprint(int parentMaxChars, int parentOverlapChars,
                                           int childMaxChars, int childOverlapChars,
                                           int maxTableRowsPerChild) {
        String raw = String.join("|",
                "p=" + parentMaxChars, "po=" + parentOverlapChars,
                "c=" + childMaxChars, "co=" + childOverlapChars,
                "t=" + maxTableRowsPerChild,
                // 切分器版本号：切分逻辑本身发生变化时（不只是参数），手工+1
                // 参数没变但代码逻辑改了的情况，这个版本号是唯一的兜底
                "v=1");
        return hashOf(raw).substring(0, 16);
    }
}
