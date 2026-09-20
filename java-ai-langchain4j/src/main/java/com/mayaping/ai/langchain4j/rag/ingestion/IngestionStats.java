package com.mayaping.ai.langchain4j.rag.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引管线统计信息：计数 + 按文档明细 + **校验告警**。
 *
 * 告警这一项不是可选的装饰。PDF解析失败是**静默**的：PDFBox抽表格不报错，
 * MinerU的txt模式丢表格不报错，扫描件OCR崩了也不报错，三者表现统一是
 * "文档里明明有，就是答不对"。没有这道显式闸门，你只能在检索效果变差时才倒查，
 * 而那时已经跨了好几个环节，根本定位不到是解析出的问题。
 *
 * 增量模式下这组数字的读法（最容易看错的地方）：
 *   - {@code documentsSkipped} 大、{@code documentsChanged} 小 → 增量生效了，省下了 embedding
 *   - {@code children} 是**本次真正重新向量化**的子块数；被复用的在 {@code childrenCarriedOver}
 *   - 想知道索引里的总量，要看 children + childrenCarriedOver，而不是只看 children
 */
public class IngestionStats {

    private final Map<String, Integer> counters = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * 按文档累计的父块/子块数，用于写入指纹账本。
     *
     * 为什么要单独记而不是复用汇总计数：账本需要知道**每份文档各自**产出了多少块。
     * 出问题时这组数字是判断"索引是否残缺"的直接依据——总量对不上时，
     * 你能立刻看出是哪份文档少了块。
     */
    private final Map<String, Integer> parentsByDoc = new LinkedHashMap<>();
    private final Map<String, Integer> childrenByDoc = new LinkedHashMap<>();

    // ==================== 计数 ====================

    public void add(String key, int delta) {
        counters.merge(key, delta, Integer::sum);
    }

    /** 覆盖式设置计数（如 published=0/1、documentsTotal=12） */
    public void put(String key, int value) {
        counters.put(key, value);
    }

    /** 非计数型的描述字段（如 mode=incremental）。与计数分开存放，避免类型污染 */
    public void label(String key, String value) {
        labels.put(key, value);
    }

    public int get(String key) {
        return counters.getOrDefault(key, 0);
    }

    public String label(String key) {
        return labels.get(key);
    }

    public Map<String, Integer> asMap() {
        return counters;
    }

    public Map<String, String> labelsAsMap() {
        return labels;
    }

    // ==================== 按文档明细 ====================

    public void addDocParents(String docId, int delta) {
        if (docId != null) {
            parentsByDoc.merge(docId, delta, Integer::sum);
        }
    }

    public void addDocChildren(String docId, int delta) {
        if (docId != null) {
            childrenByDoc.merge(docId, delta, Integer::sum);
        }
    }

    public int docParentCount(String docId) {
        return parentsByDoc.getOrDefault(docId, 0);
    }

    public int docChildCount(String docId) {
        return childrenByDoc.getOrDefault(docId, 0);
    }

    // ==================== 告警 ====================

    /**
     * 登记一条校验告警。告警不代表索引失败，但每一条都对应一个"内容可能不对"的位置
     */
    public void warn(String message) {
        warnings.add(message);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
