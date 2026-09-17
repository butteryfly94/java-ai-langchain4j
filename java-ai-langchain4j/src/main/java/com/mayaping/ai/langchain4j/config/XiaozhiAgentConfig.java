package com.mayaping.ai.langchain4j.config;

import com.mayaping.ai.langchain4j.store.MongoChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 小智Agent配置：聊天记忆（MongoDB持久化）
 * 检索链路已升级为RAGConfig中的RetrievalAugmentor（混合检索+RRF+Rerank），
 * 在XiaozhiAgent的@AiService注解上通过retrievalAugmentor属性挂载
 */
@Configuration
public class XiaozhiAgentConfig {

    @Autowired
    private MongoChatMemoryStore mongoChatMemoryStore;

    @Bean
    public ChatMemoryProvider chatMemoryProviderXiaozhi() {

        return memoryId ->
                MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(mongoChatMemoryStore)
                        .build();

    }
}
