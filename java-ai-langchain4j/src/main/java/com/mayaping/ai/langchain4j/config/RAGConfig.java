package com.mayaping.ai.langchain4j.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mayaping.ai.langchain4j.rag.aggregator.RrfRerankAggregator;
import com.mayaping.ai.langchain4j.rag.citation.CitationRecorder;
import com.mayaping.ai.langchain4j.rag.citation.RecordingContentAggregator;
import dev.langchain4j.community.model.dashscope.QwenScoringModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 在线问答链路的检索组件装配。
 *
 * 组件拆分（改造后）：两个检索器与聚合器各自独立成bean，供两条链路共享：
 *   - 固定RAG：retrievalAugmentorXiaozhi 把它们组装成完整管线
 *   - Agentic：HybridRetrievalService 直接复用同一批bean
 *
 * 共享的意义在于两条链路的检索**能力**必须一致，差异只保留在"谁决定何时检索"。
 * 改造前检索器与聚合器是 retrievalAugmentorXiaozhi 方法体内的局部变量，容器里拿不到，
 * 导致Agentic链路只能退回裸embeddingStore检索，检索质量被关在了RetrievalAugmentor
 * 这个接口里面。
 *
 * 固定RAG完整管线：
 * 用户提问 → 查询扩展(LLM生成多个变体查询)
 *        → DefaultQueryRouter 同时路由到两个检索器：
 *           ① kNN向量检索器（语义召回，top-20）
 *           ② BM25全文检索器（关键词召回，top-20）
 *        → RrfRerankAggregator（RRF融合 → 父块扩展 → gte-rerank重排，取top-3父块）
 *        → DefaultContentInjector 注入prompt
 *
 * 说明：ES服务端RRF混合检索需要企业许可证，故在应用层融合，效果等价且不依赖商业组件
 */
@Configuration
public class RAGConfig {

    @Bean
    public ScoringModel scoringModel() {
        // 阿里百炼 gte-rerank 交叉编码器重排模型
        return QwenScoringModel.builder()
                .apiKey(System.getenv("DASH_SCOPE_API_KEY"))
                .modelName("gte-rerank-v2")
                .build();
    }

    /**
     * ① kNN向量检索器：语义相似召回（"睡不着觉"能召回"睡眠障碍"类内容）
     */
    @Bean
    public ContentRetriever knnContentRetriever(ElasticsearchClient elasticsearchClient,
                                                EmbeddingModel embeddingModel,
                                                @Value("${rag.index.children}") String childrenIndex,
                                                @Value("${rag.retrieval.max-results-per-retriever}") int maxResultsPerRetriever) {
        return ElasticsearchContentRetriever.builder()
                .client(elasticsearchClient)
                .embeddingModel(embeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .indexName(childrenIndex)
                .maxResults(maxResultsPerRetriever)
                .minScore(0.0)
                .build();
    }

    /**
     * ② BM25全文检索器：精确关键词召回（专有名词、药品名、科室名等命中更准）
     */
    @Bean
    public ContentRetriever bm25ContentRetriever(ElasticsearchClient elasticsearchClient,
                                                 @Value("${rag.index.children}") String childrenIndex,
                                                 @Value("${rag.retrieval.max-results-per-retriever}") int maxResultsPerRetriever) {
        return ElasticsearchContentRetriever.builder()
                .client(elasticsearchClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .indexName(childrenIndex)
                .maxResults(maxResultsPerRetriever)
                .build();
    }

    /**
     * 自定义聚合器：RRF融合 → 父块扩展 → rerank重排
     */
    @Bean
    public RrfRerankAggregator rrfRerankAggregator(ElasticsearchClient elasticsearchClient,
                                                   ScoringModel scoringModel,
                                                   @Value("${rag.index.parents}") String parentsIndex,
                                                   @Value("${rag.retrieval.rrf-k}") int rrfK,
                                                   @Value("${rag.retrieval.top-children}") int topChildren,
                                                   @Value("${rag.retrieval.top-parents}") int topParents,
                                                   @Value("${rag.retrieval.rerank-enabled:true}") boolean rerankEnabled) {
        return new RrfRerankAggregator(
                elasticsearchClient, scoringModel, parentsIndex, rrfK, topChildren, topParents, rerankEnabled);
    }

    /**
     * 带引用溯源的聚合器：行为与 rrfRerankAggregator 完全一致，额外把rerank后的
     * 最终父块登记为引用来源，供流末尾输出给前端。
     *
     * 只包给固定RAG链路用。Agentic链路的检索工具自己有 @ToolMemoryId 拿会话ID，
     * 直接登记即可，不必绕道这里。
     */
    @Bean
    public RecordingContentAggregator recordingContentAggregator(RrfRerankAggregator rrfRerankAggregator,
                                                                 CitationRecorder citationRecorder) {
        return new RecordingContentAggregator(rrfRerankAggregator, citationRecorder);
    }

    /**
     * 固定RAG链路的完整管线。此处按 ContentAggregator 接口注入而非具体类型——
     * 换聚合器实现（如加引用溯源装饰器）时本方法无需改动。
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentorXiaozhi(
            @Qualifier("knnContentRetriever") ContentRetriever knnRetriever,
            @Qualifier("bm25ContentRetriever") ContentRetriever bm25Retriever,
            @Qualifier("recordingContentAggregator") ContentAggregator contentAggregator,
            @Qualifier("qwenChatModel") ChatModel qwenChatModel) {

        // 查询扩展：LLM把用户问题改写为多个语义变体，提高召回覆盖面
        QueryTransformer queryTransformer = ExpandingQueryTransformer.builder()
                .chatModel(qwenChatModel)
                .build();

        // 每个查询变体同时路由到双检索器
        QueryRouter queryRouter = new DefaultQueryRouter(knnRetriever, bm25Retriever);

        ContentInjector contentInjector = new DefaultContentInjector();

        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .contentAggregator(contentAggregator)
                .contentInjector(contentInjector)
                .build();
    }
}
