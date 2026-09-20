package com.mayaping.ai.langchain4j.rag.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重复查询拦截：同一个会话在时间窗内用相同查询词再次检索时，直接返回上次结果，不再真跑检索。
 *
 * 为什么用时间窗，而不是"每轮对话清空"：工具拿不到"一轮对话"的边界——
 * @ToolMemoryId 给的是整个会话的memoryId，且工具方法本身感知不到LLM何时结束一轮。
 * 时间窗是折中方案：短时间内（默认120秒）的相同查询几乎必然是LLM在同一轮里反复试探，
 * 而跨轮次的合法重查（用户过一会儿又问同一件事）不会被误杀。
 *
 * 成本考量：一次检索 = 2次ES查询 + 1次rerank API调用，且agentic循环里每轮都要
 * 携带线性增长的对话上下文。循环5次就是10次ES + 5次rerank。
 * 拦掉重复查询是这里性价比最高的一刀，不需要动提示词。
 *
 * 注意返回的是真实的上次结果，而不是一句"查询重复了"——后者会让模型失去数据、
 * 反而更用力地重试，把一个可控的重复变成死循环。
 */
@Component
public class QueryDedupCache {

    private static final Logger log = LoggerFactory.getLogger(QueryDedupCache.class);

    /** 每个会话保留的近期查询数 */
    private static final int MAX_ENTRIES_PER_MEMORY = 5;
    /** 同时跟踪的会话数上限，超出按LRU淘汰，防止只增不减 */
    private static final int MAX_TRACKED_MEMORY_IDS = 200;
    /** 单条结果缓存上限，超出截断——只用于提示模型"结果已在上面"，不需要完整副本 */
    private static final int MAX_CACHED_RESULT_CHARS = 4000;

    private final long windowMillis;

    private final Map<Object, Map<String, CachedResult>> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Map<String, CachedResult>> eldest) {
                    return size() > MAX_TRACKED_MEMORY_IDS;
                }
            });

    private record CachedResult(String result, long timestamp) {

        boolean isFresh(long now, long windowMillis) {
            return now - timestamp <= windowMillis;
        }
    }

    public QueryDedupCache(@Value("${rag.tool.dedup-window-seconds:120}") long windowSeconds) {
        this.windowMillis = windowSeconds * 1000L;
    }

    /**
     * 查近期是否有相同查询的结果。
     *
     * @return 时间窗内的上次结果；没有则返回null，调用方应正常执行检索并调 {@link #record}
     */
    public String getFreshResult(Object memoryId, String query) {
        if (memoryId == null || query == null) {
            return null;
        }
        Map<String, CachedResult> perMemory = cache.get(memoryId);
        if (perMemory == null) {
            return null;
        }

        synchronized (perMemory) {
            CachedResult cached = perMemory.get(normalize(query));
            if (cached == null) {
                return null;
            }
            if (!cached.isFresh(System.currentTimeMillis(), windowMillis)) {
                // 过期即移除，避免窗口外的陈旧结果继续命中
                perMemory.remove(normalize(query));
                return null;
            }
            log.info("命中近期重复查询，跳过检索直接复用上次结果。memoryId={}，查询：{}", memoryId, query);
            return cached.result();
        }
    }

    /**
     * 登记一次真实执行的检索结果
     */
    public void record(Object memoryId, String query, String result) {
        if (memoryId == null || query == null || result == null) {
            return;
        }
        Map<String, CachedResult> perMemory =
                cache.computeIfAbsent(memoryId, id -> Collections.synchronizedMap(
                        new LinkedHashMap<>(8, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
                                return size() > MAX_ENTRIES_PER_MEMORY;
                            }
                        }));

        String capped = result.length() <= MAX_CACHED_RESULT_CHARS
                ? result
                : result.substring(0, MAX_CACHED_RESULT_CHARS);

        perMemory.put(normalize(query), new CachedResult(capped, System.currentTimeMillis()));
    }

    /**
     * 查询归一化：去掉首尾空白与多余空格，让"心内科 门诊时间"和"心内科门诊时间"视为同一个查询。
     * 大小写不敏感，覆盖英文药品名/缩写。
     */
    private static String normalize(String query) {
        return query.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
