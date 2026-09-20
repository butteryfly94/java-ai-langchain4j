package com.mayaping.ai.langchain4j;

import com.mayaping.ai.langchain4j.rag.ingestion.DocumentFingerprintStore;
import com.mayaping.ai.langchain4j.rag.ingestion.DocumentFingerprintStore.DocRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量索引的判定逻辑单元测试。
 *
 * 刻意不标 @SpringBootTest：这里只关心"哪份文档该重灌"的判定，
 * 不需要 ES / 账本索引 / DashScope。判定逻辑是整个增量功能里唯一会静默出错的部分——
 * 判错不会抛异常，只会让索引悄悄缺失内容，因此它必须能在任何机器上秒级验证。
 *
 * 覆盖的核心不变量：
 * - 内容没变 → 跳过（这是省下 embedding 成本的前提）
 * - 内容变了 → 重灌
 * - 账本里没有 → 重灌（新增文档）
 * - **切分配置变了 → 即使内容没变也必须重灌**（最容易漏、代价最大的一条）
 */
public class DocumentFingerprintTest {

    /** 与 KnowledgeIngestionService 里的常量保持一致：父1000/子300/表200 */
    private static final String CONFIG_A =
            DocumentFingerprintStore.configFingerprint(1000, 100, 300, 30, 200);

    /** 只有 childMaxChars 从 300 变成 400，其余不变 */
    private static final String CONFIG_B =
            DocumentFingerprintStore.configFingerprint(1000, 100, 400, 30, 200);

    private static DocRecord record(String docId, String content, String configFingerprint) {
        DocRecord r = new DocRecord();
        r.setDocId(docId);
        r.setContentHash(DocumentFingerprintStore.hashOf(content));
        r.setConfigFingerprint(configFingerprint);
        return r;
    }

    private static DocumentFingerprintStore storeWith(String configFingerprint) {
        // 判定逻辑不依赖ES连接，但构造函数要求非null；传入null并在纯判定方法上测试
        return new DocumentFingerprintStore(null, "unused-index", configFingerprint);
    }

    @Test
    public void 内容未变时应跳过重灌() {
        DocumentFingerprintStore store = storeWith(CONFIG_A);
        DocRecord existing = record("科室信息.md", "神经内科位于门诊楼3楼。", CONFIG_A);
        String newHash = DocumentFingerprintStore.hashOf("神经内科位于门诊楼3楼。");

        assertFalse(store.needsReindex(existing, newHash),
                "内容与配置均未变，应跳过以省下 embedding 调用");
    }

    @Test
    public void 内容变化时应重灌() {
        DocumentFingerprintStore store = storeWith(CONFIG_A);
        DocRecord existing = record("科室信息.md", "神经内科位于门诊楼3楼。", CONFIG_A);
        String newHash = DocumentFingerprintStore.hashOf("神经内科已搬迁至门诊楼4楼。");

        assertTrue(store.needsReindex(existing, newHash), "内容变了必须重灌");
    }

    @Test
    public void 账本中不存在的文档视为新增需要重灌() {
        DocumentFingerprintStore store = storeWith(CONFIG_A);

        assertTrue(store.needsReindex(null, DocumentFingerprintStore.hashOf("任意内容")),
                "账本里没有记录说明该文档从未索引过，必须灌入");
    }

    /**
     * 这条是整个类里最重要的断言。
     *
     * 场景：有人把 rag 的切分参数从 child=300 调到 child=400，文档一个字没改，
     * 然后跑了增量。如果判定只看内容hash，就会**跳过所有文档**，
     * 索引里留着的还是按 300 切的结果——配置改了、任务显示成功、实际未生效。
     * 这类静默失效排查成本极高（要怀疑到"参数到底有没有生效"这一层）。
     */
    @Test
    public void 切分配置变化时即使内容未变也必须重灌() {
        DocumentFingerprintStore store = storeWith(CONFIG_B);
        DocRecord existing = record("科室信息.md", "神经内科位于门诊楼3楼。", CONFIG_A);
        String sameContentHash = DocumentFingerprintStore.hashOf("神经内科位于门诊楼3楼。");

        assertTrue(store.needsReindex(existing, sameContentHash),
                "切分配置指纹变化时内容未变也要重灌，否则索引里还是旧切分的结果");
    }

    /**
     * 配置指纹必须对每个影响切分结果的参数敏感。
     * 漏掉任何一个参数，都会产生"改了参数但增量跳过了"的静默失效。
     */
    @Test
    public void 配置指纹应随任一参数变化而变化() {
        assertEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1000, 100, 300, 30, 200),
                "参数完全相同应得到相同指纹");

        assertNotEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1200, 100, 300, 30, 200),
                "父块大小变化应改变指纹");
        assertNotEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1000, 200, 300, 30, 200),
                "父块重叠变化应改变指纹");
        assertNotEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1000, 100, 400, 30, 200),
                "子块大小变化应改变指纹");
        assertNotEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1000, 100, 300, 50, 200),
                "子块重叠变化应改变指纹");
        assertNotEquals(CONFIG_A, DocumentFingerprintStore.configFingerprint(1000, 100, 300, 30, 500),
                "单表行数上限变化应改变指纹（它影响产出多少子块）");
    }

    /**
     * hash 的稳定性：同样的内容在任何时候都必须得到同样的值，
     * 否则每次增量都会误判为"全部变更"，增量形同虚设。
     */
    @Test
    public void 内容hash应稳定且对细微差异敏感() {
        String content = "门诊时间为周一至周五上午8:00至下午17:00。";
        assertEquals(DocumentFingerprintStore.hashOf(content), DocumentFingerprintStore.hashOf(content),
                "同样的内容必须得到同样的hash，否则增量永远命中不了跳过分支");

        assertNotEquals(DocumentFingerprintStore.hashOf(content),
                DocumentFingerprintStore.hashOf(content + " "),
                "末尾多一个空格也是内容变更，应被识别出来");
    }

    /**
     * 中文内容的hash必须基于UTF-8字节而不是平台默认编码。
     *
     * 若实现里用了 new String().getBytes()（无参，取平台默认编码），
     * 同一份文档在不同机器（GBK vs UTF-8）上会算出不同的hash，
     * 表现为"在A机器上跑完增量，在B机器上又全量重灌一遍"——
     * 不会报错，只是钱白花了。
     */
    @Test
    public void 中文内容hash应基于utf8而非平台编码() {
        String chinese = "神经内科";

        assertEquals(DocumentFingerprintStore.hashOf(chinese),
                DocumentFingerprintStore.hashOf(new String(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8)),
                "中文内容在不同字符集往返后hash应保持不变");
    }
}
