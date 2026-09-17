package com.mayaping.ai.langchain4j.rag.aggregator;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 自定义内容聚合器：RRF融合 → 父块扩展 → Rerank重排（在线检索链路的技术核心）
 *
 * 输入：Map<Query, Collection<List<Content>>>，即"每个查询 × 每个检索器"的命中列表
 * （查询扩展生成多个变体查询 × kNN向量/BM25双检索器）
 *
 * 处理流程：
 * 1. RRF融合：score = Σ 1/(k + rank)，k默认60。一个子块被越多检索器、越多查询变体命中且排名越靠前，融合分越高
 * 2. 父块扩展：取融合分最高的topN子块，按parentId去重后从ES parents索引回查父块原文
 *    （检索用子块保证召回精度，上下文用父块保证语义完整——LangChain4j无内置实现，此处自定义）
 * 3. Rerank重排：用gte-rerank交叉编码器对"查询-父块"对精细打分，取topM注入prompt
 *
 * rerank失败时降级为RRF顺序（不影响可用性）
 */
public class RrfRerankAggregator implements ContentAggregator {

    private static final Logger log = LoggerFactory.getLogger(RrfRerankAggregator.class);

    private final ElasticsearchClient elasticsearchClient;
    private final ScoringModel scoringModel;
    private final String parentsIndex;
    private final int rrfK;
    private final int topChildren;
    private final int topParents;
    private final boolean rerankEnabled;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParentDoc {
        private String text;
        private String docId;
        private String source;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }


    public RrfRerankAggregator(ElasticsearchClient elasticsearchClient,
                               ScoringModel scoringModel,
                               String parentsIndex,
                               int rrfK,
                               int topChildren,
                               int topParents,
                               boolean rerankEnabled) {
        this.elasticsearchClient = elasticsearchClient;
        this.scoringModel = scoringModel;
        this.parentsIndex = parentsIndex;
        this.rrfK = rrfK;
        this.topChildren = topChildren;
        this.topParents = topParents;
        this.rerankEnabled = rerankEnabled;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        // ===== 第一步：RRF融合（跨查询变体 × 跨检索器）=====
        // 以子块文本为key去重累计：同一子块被kNN和BM25同时命中，或被多个查询变体命中，分数累加
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Content> contentByText = new HashMap<>();

        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            for (List<Content> rankedList : entry.getValue()) {
                for (int rank = 0; rank < rankedList.size(); rank++) {
                    Content content = rankedList.get(rank);
                    String key = content.textSegment().text();
                    // rank从0计，公式中名次从1计
                    rrfScores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                    contentByText.putIfAbsent(key, content);
                }
            }
        }

        List<Content> topChildren = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(this.topChildren)
                .map(e -> contentByText.get(e.getKey()))
                .toList();

        // ===== 第二步：父块扩展（按parentId去重回查原文）=====
        LinkedHashSet<String> parentIds = new LinkedHashSet<>();
        for (Content child : topChildren) {
            String parentId = child.textSegment().metadata().getString("parentId");
            if (parentId != null) {
                parentIds.add(parentId);
            }
        }

        List<ParentDoc> parents = fetchParents(parentIds);
        if (parents.isEmpty()) {
            // 父块索引为空（未跑索引管线？），降级返回子块本身
            log.warn("父块索引未命中，降级返回RRF融合后的子块");
            return topChildren;
        }

        // ===== 第三步：Rerank重排（交叉编码器对查询-父块对精细打分）=====
        String rerankQuery = queryToContents.keySet().iterator().next().text();
        List<TextSegment> parentSegments = parents.stream()
                .map(p -> TextSegment.from(p.getText(),
                        new Metadata().put("docId", p.getDocId()).put("source", p.getSource())))
                .toList();

        if (!rerankEnabled) {
            return parentSegments.stream().limit(topParents).map(Content::from).toList();
        }

        try {
            Response<List<Double>> scores = scoringModel.scoreAll(parentSegments, rerankQuery);
            List<Double> scoreList = scores.content();

            Map<TextSegment, Double> segmentScores = new LinkedHashMap<>();
            for (int i = 0; i < parentSegments.size() && i < scoreList.size(); i++) {
                segmentScores.put(parentSegments.get(i), scoreList.get(i));
            }

            return segmentScores.entrySet().stream()
                    .sorted(Map.Entry.<TextSegment, Double>comparingByValue().reversed())
                    .limit(topParents)
                    .map(e -> Content.from(e.getKey(),
                            Map.of(ContentMetadata.RERANKED_SCORE, e.getValue())))
                    .toList();
        } catch (Exception e) {
            // rerank失败降级：保持RRF顺序，不影响主链路可用性
            log.warn("Rerank失败，降级为RRF融合顺序：{}", e.getMessage());
            return parentSegments.stream().limit(topParents).map(Content::from).toList();
        }
    }

    @Override
    public CompletableFuture<List<Content>> aggregateAsync(Map<Query, Collection<List<Content>>> queryToContents) {
        // 聚合器内有ES网络调用与rerank模型调用，异步模式下放到独立线程执行避免阻塞
        return CompletableFuture.supplyAsync(() -> aggregate(queryToContents));
    }

    /**
     * 从parents索引按id批量回查父块原文
     */
    private List<ParentDoc> fetchParents(LinkedHashSet<String> parentIds) {
        if (parentIds.isEmpty()) {
            return List.of();
        }
        try {
            return elasticsearchClient.mget(m -> m.index(parentsIndex).ids(new ArrayList<>(parentIds)), ParentDoc.class)
                    .docs().stream()
                    .filter(item -> !item.isFailure())
                    .map(item -> item.result())      // GetResult<ParentDoc>：{_index,_id,_source}包装对象
                    .filter(Objects::nonNull)
                    .map(result -> result.source())  // 真正的文档内容在source()里
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("父块回查失败：{}", e.getMessage());
            return List.of();
        }
    }
}
