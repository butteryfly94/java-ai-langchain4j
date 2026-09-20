package com.mayaping.ai.langchain4j.rag.retrieval;

import com.mayaping.ai.langchain4j.rag.aggregator.RrfRerankAggregator;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务：kNN/BM25双路召回 → RRF融合 → 父块扩展 → rerank重排
 *
 * 为什么要有这一层——两条链路必须共享同一套检索能力，差异只应保留在"谁决定何时检索"：
 *   固定RAG ：查询扩展 → 【本服务】 → 注入prompt
 *   Agentic ：LLM生成tool参数 → 【本服务】
 *
 * 接缝刻意开在"查询扩展之下"：agentic模式下LLM产出检索关键词这一步本身就是query
 * transformation，再套一层ExpandingQueryTransformer是重复劳动，且每次工具调用都要
 * 多付一次LLM往返。所以查询扩展留在固定链路，本服务只负责"给定查询，返回最优父块"。
 *
 * 改造前KnowledgeSearchTool用的是裸embeddingStore.search()（只有kNN、只返回child碎片、
 * 无RRF无rerank），检索质量严格劣于固定链路，导致"固定 vs agentic"的对比实验里
 * 编排方式和检索质量两个变量同时变化，结论不可解释。此服务消除了该不对称。
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final ContentRetriever knnRetriever;
    private final ContentRetriever bm25Retriever;
    private final RrfRerankAggregator aggregator;
    private final int agenticTopParents;

    public HybridRetrievalService(@Qualifier("knnContentRetriever") ContentRetriever knnRetriever,
                                  @Qualifier("bm25ContentRetriever") ContentRetriever bm25Retriever,
                                  RrfRerankAggregator aggregator,
                                  @Value("${rag.retrieval.agentic-top-parents:5}") int agenticTopParents) {
        this.knnRetriever = knnRetriever;
        this.bm25Retriever = bm25Retriever;
        this.aggregator = aggregator;
        this.agenticTopParents = agenticTopParents;
    }

    /**
     * agentic链路入口：LLM已经自己改写过查询了，直接用
     */
    public List<Content> retrieve(String query) {
        return retrieve(Query.from(query), agenticTopParents);
    }

    /**
     * 指定父块数量的检索，供工具按需调整上下文量
     */
    public List<Content> retrieve(String query, int topParents) {
        return retrieve(Query.from(query), topParents);
    }

    /**
     * 单查询检索：双检索器并行度交给调用方，这里顺序调用保持与固定链路一致的行为
     */
    public List<Content> retrieve(Query query, int topParents) {
        Map<Query, Collection<List<Content>>> queryToContents = new LinkedHashMap<>();
        queryToContents.put(query, retrieveFromBoth(query));
        return aggregator.aggregate(queryToContents, topParents);
    }

    /**
     * 多查询检索：等价于固定链路"查询扩展产出多个变体"后的聚合行为
     */
    public List<Content> retrieve(Collection<Query> queries, int topParents) {
        Map<Query, Collection<List<Content>>> queryToContents = new LinkedHashMap<>();
        for (Query query : queries) {
            queryToContents.put(query, retrieveFromBoth(query));
        }
        return aggregator.aggregate(queryToContents, topParents);
    }

    /**
     * 同时打kNN与BM25两个检索器。
     * 单个检索器失败时降级为另一个的结果（混合检索的价值就在于两路互为备份），
     * 两个都失败才向上抛——检索失败不该被静默吞掉变成"知识库没有这条"。
     */
    private List<List<Content>> retrieveFromBoth(Query query) {
        List<List<Content>> results = new ArrayList<>(2);

        List<Content> knn = safeRetrieve(knnRetriever, query, "kNN");
        if (knn != null) {
            results.add(knn);
        }
        List<Content> bm25 = safeRetrieve(bm25Retriever, query, "BM25");
        if (bm25 != null) {
            results.add(bm25);
        }

        if (results.isEmpty()) {
            throw new IllegalStateException("kNN与BM25检索器均失败，查询：" + query.text());
        }
        return results;
    }

    private List<Content> safeRetrieve(ContentRetriever retriever, Query query, String name) {
        try {
            return retriever.retrieve(query);
        } catch (Exception e) {
            log.warn("{}检索器失败，降级为单路召回：{}", name, e.getMessage());
            return null;
        }
    }
}
