package com.mayaping.ai.langchain4j.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mayaping.ai.langchain4j.rag.aggregator.RrfRerankAggregator;
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
 * 在线问答链路（完整RetrievalAugmentor管线）：
 *
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

    @Bean
    public RetrievalAugmentor retrievalAugmentorXiaozhi(ElasticsearchClient elasticsearchClient,
                                                        EmbeddingModel embeddingModel,
                                                        @Qualifier("qwenChatModel") ChatModel qwenChatModel,
                                                        ScoringModel scoringModel,
                                                        @Value("${rag.index.children}") String childrenIndex,
                                                        @Value("${rag.index.parents}") String parentsIndex,
                                                        @Value("${rag.retrieval.max-results-per-retriever}") int maxResultsPerRetriever,
                                                        @Value("${rag.retrieval.rrf-k}") int rrfK,
                                                        @Value("${rag.retrieval.top-children}") int topChildren,
                                                        @Value("${rag.retrieval.top-parents}") int topParents,
                                                        @Value("${rag.retrieval.rerank-enabled:true}") boolean rerankEnabled) {

        // ① kNN向量检索器：语义相似召回（"睡不着觉"能召回"睡眠障碍"类内容）
        ContentRetriever knnRetriever = ElasticsearchContentRetriever.builder()
                .client(elasticsearchClient)
                .embeddingModel(embeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .indexName(childrenIndex)
                .maxResults(maxResultsPerRetriever)
                .minScore(0.0)
                .build();

        // ② BM25全文检索器：精确关键词召回（专有名词、药品名、科室名等命中更准）
        ContentRetriever bm25Retriever = ElasticsearchContentRetriever.builder()
                .client(elasticsearchClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .indexName(childrenIndex)
                .maxResults(maxResultsPerRetriever)
                .build();

        // 查询扩展：LLM把用户问题改写为多个语义变体，提高召回覆盖面
        QueryTransformer queryTransformer = ExpandingQueryTransformer.builder()
                .chatModel(qwenChatModel)
                .build();

        // 每个查询变体同时路由到双检索器
        QueryRouter queryRouter = new DefaultQueryRouter(knnRetriever, bm25Retriever);

        // 自定义聚合器：RRF融合 → 父块扩展 → rerank重排
        ContentAggregator contentAggregator = new RrfRerankAggregator(
                elasticsearchClient, scoringModel, parentsIndex, rrfK, topChildren, topParents, rerankEnabled);

        ContentInjector contentInjector = new DefaultContentInjector();

        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .contentAggregator(contentAggregator)
                .contentInjector(contentInjector)
                .build();
    }
}
