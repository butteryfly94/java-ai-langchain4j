package com.mayaping.ai.langchain4j.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置：Elasticsearch（替代Pinecone）
 * children索引同时存储向量与文本，供kNN向量检索与BM25全文检索共用（混合检索的基础）
 */
@Configuration
public class EmbeddingStoreConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(ElasticsearchClient elasticsearchClient,
                                                      @Value("${rag.index.children}") String childrenIndex) {
        return ElasticsearchEmbeddingStore.builder()
                .client(elasticsearchClient)
                .indexName(childrenIndex)
                .build();
    }
}
