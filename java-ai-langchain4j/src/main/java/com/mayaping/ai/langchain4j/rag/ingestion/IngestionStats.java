package com.mayaping.ai.langchain4j.rag.ingestion;

import java.util.HashMap;
import java.util.Map;

/**
 * 索引管线统计信息：文档数/父块数/子块数
 */
public class IngestionStats {

    private final Map<String, Integer> stats = new HashMap<>();

    public void add(String key, int delta) {
        stats.merge(key, delta, Integer::sum);
    }

    public int get(String key) {
        return stats.getOrDefault(key, 0);
    }

    public Map<String, Integer> asMap() {
        return stats;
    }
}
