package com.mayaping.ai.langchain4j.rag.citation;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引用来源收集器：按会话暂存本次回答实际用到的知识片段出处。
 *
 * 两条链路都往里写，最终由 controller 在流末尾统一输出：
 *   - 固定RAG：{@link RecordingContentAggregator} 记录rerank后的父块
 *   - Agentic：{@link com.mayaping.ai.langchain4j.rag.tools.KnowledgeSearchTool}
 *     通过 @ToolMemoryId 拿到会话ID后登记
 *
 * 为何要 drain 而不是 get：一轮对话结束后必须清空，否则来源会在多轮之间累积，
 * 第二轮的答案会挂着第一轮的出处。drain（取出即清）让"这轮的来源"语义明确。
 */
@Component
public class CitationRecorder {

    private static final Logger log = LoggerFactory.getLogger(CitationRecorder.class);

    /** 单个会话最多保留的来源条数，避免一次检索命中几十个父块时前端卡片刷屏 */
    private static final int MAX_SOURCES_PER_MEMORY = 8;
    /** 同时跟踪的会话数上限，超出按LRU淘汰，防止只增不减 */
    private static final int MAX_TRACKED_MEMORY_IDS = 512;

    private final Map<Object, List<SourceRef>> byMemoryId =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, List<SourceRef>> eldest) {
                    return size() > MAX_TRACKED_MEMORY_IDS;
                }
            });

    /**
     * 记录一次检索产出的来源。
     *
     * @param memoryId 会话ID；为空时直接跳过——宁可没有引用，也不能把来源记到别的会话头上
     */
    public void record(Object memoryId, List<Content> contents) {
        if (memoryId == null || contents == null || contents.isEmpty()) {
            return;
        }
        List<SourceRef> refs = new ArrayList<>(contents.size());
        for (Content content : contents) {
            SourceRef ref = toSourceRef(content);
            if (ref != null) {
                refs.add(ref);
            }
        }
        if (!refs.isEmpty()) {
            merge(memoryId, refs);
        }
    }

    /**
     * 取出并清空该会话已收集的来源。供controller在流末尾调用。
     */
    public List<SourceRef> drain(Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        List<SourceRef> refs = byMemoryId.remove(memoryId);
        return refs == null ? List.of() : List.copyOf(refs);
    }

    /**
     * 合并进已有列表，按 (文档, 页码) 去重并保持首次出现顺序。
     * Agentic链路一轮可能调多次检索工具，不去重会重复列出同一页。
     */
    private void merge(Object memoryId, List<SourceRef> incoming) {
        List<SourceRef> existing = byMemoryId.computeIfAbsent(memoryId, id -> new ArrayList<>());
        synchronized (existing) {
            Set<String> seen = new LinkedHashSet<>();
            for (SourceRef ref : existing) {
                seen.add(ref.dedupKey());
            }
            for (SourceRef ref : incoming) {
                if (existing.size() >= MAX_SOURCES_PER_MEMORY) {
                    break;
                }
                if (seen.add(ref.dedupKey())) {
                    existing.add(ref);
                }
            }
        }
    }

    private SourceRef toSourceRef(Content content) {
        if (content == null || content.textSegment() == null) {
            return null;
        }
        Metadata metadata = content.textSegment().metadata();
        String docId = metadata.getString("docId");
        String source = metadata.getString("source");
        if ((source == null || source.isBlank()) && (docId == null || docId.isBlank())) {
            log.debug("检索结果缺少docId/source元数据，跳过该条引用");
            return null;
        }
        Integer page = metadata.getInteger("page");
        return SourceRef.of(docId, source, page, content.textSegment().text());
    }
}
