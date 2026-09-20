package com.mayaping.ai.langchain4j.config;

import com.mayaping.ai.langchain4j.assistant.XiaozhiAgent;
import com.mayaping.ai.langchain4j.rag.tools.ToolLoopGuard;
import com.mayaping.ai.langchain4j.store.MongoChatMemoryStore;
import com.mayaping.ai.langchain4j.tools.AppointmentTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 小智固定RAG助手配置：聊天记忆（MongoDB持久化）+ 助手装配
 *
 * 装配方式为编程式而非 @AiService 注解。原因见 {@link XiaozhiAgent} 的类注释：
 * 注解不支持工具循环保护参数。两个助手（固定RAG与Agentic RAG）因此写法统一，
 * 都在各自的Config里用 AiServices.builder 显式声明依赖，谁挂了什么一目了然。
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

    /**
     * 固定RAG助手：挂载完整检索管线 + 预约挂号工具。
     *
     * 与 AgenticRagAssistant 的关键区别：这里的检索由 retrievalAugmentorXiaozhi 在
     * 每次调用前自动执行（一次检索一次回答），而 Agentic 那条把检索作为工具交给LLM自主调度。
     * 但两条链路底层的检索器与聚合器是同一批bean（见 {@link RAGConfig}），
     * 检索能力一致，差异只在编排。
     */
    @Bean
    public XiaozhiAgent xiaozhiAgent(
            @Qualifier("qwenStreamingChatModel") StreamingChatModel qwenStreamingChatModel,
            @Qualifier("chatMemoryProviderXiaozhi") ChatMemoryProvider chatMemoryProvider,
            AppointmentTools appointmentTools,
            @Qualifier("retrievalAugmentorXiaozhi") RetrievalAugmentor retrievalAugmentor,
            ToolLoopGuard toolLoopGuard,
            @Value("${rag.tool.max-round-trips:5}") int maxRoundTrips,
            @Value("${rag.tool.max-sequential-invocations:3}") int maxSequentialInvocations) {

        return AiServices.builder(XiaozhiAgent.class)
                .streamingChatModel(qwenStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(appointmentTools)
                /* 完整检索链路：查询扩展 → kNN/BM25双路召回 → RRF融合 → 父块扩展 → rerank重排 */
                .retrievalAugmentor(retrievalAugmentor)
                // 固定链路也会调预约工具，同样需要循环兜底
                .maxToolCallingRoundTrips(maxRoundTrips)
                .maxSequentialToolsInvocations(maxSequentialInvocations)
                .beforeToolExecution(toolLoopGuard)
                .build();
    }
}
