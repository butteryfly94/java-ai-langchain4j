package com.mayaping.ai.langchain4j.config;

import com.mayaping.ai.langchain4j.assistant.AgenticRagAssistant;
import com.mayaping.ai.langchain4j.rag.tools.KnowledgeSearchTool;
import com.mayaping.ai.langchain4j.rag.tools.ToolLoopGuard;
import com.mayaping.ai.langchain4j.tools.AppointmentTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * Agentic RAG + MCP配置
 *
 * 通过MCP协议（stdio传输）启动官方filesystem server，把"列出/读取知识库文件"
 * 作为标准协议工具暴露给LLM，与本地@Tool（知识库检索、预约挂号）共存。
 * LLM自主调度全部工具——这就是Agentic RAG：检索是决策，不是固定管线。
 *
 * 需要本机安装Node.js（npx启动server）。rag.mcp.enabled=false可整体关闭。
 */
@Configuration
@ConditionalOnProperty(name = "rag.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class AgenticRagConfig {

    private static final Logger log = LoggerFactory.getLogger(AgenticRagConfig.class);

    @Value("${rag.knowledge-dir}")
    private String knowledgeDir;

    @Bean(destroyMethod = "close")
    public McpClient filesystemMcpClient() {
        // Windows下npx是npx.cmd，ProcessBuilder无法直接执行，需经cmd /c包装
        StdioMcpTransport transport = StdioMcpTransport.builder()
                .command(List.of("cmd", "/c", "npx", "-y",
                        "@modelcontextprotocol/server-filesystem",
                        Path.of(knowledgeDir).toAbsolutePath().toString()))
                .logEvents(true)
                .build();

        McpClient mcpClient = DefaultMcpClient.builder()
                .key("filesystem-mcp")
                .transport(transport)
                .build();
        log.info("MCP filesystem server已启动，暴露知识库目录：{}", Path.of(knowledgeDir).toAbsolutePath());
        return mcpClient;
    }

    @Bean
    public McpToolProvider mcpToolProvider(McpClient filesystemMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(filesystemMcpClient)
                .build();
    }

    /**
     * Agentic RAG助手：程序化装配（而非@AiService注解），
     * 以便和MCP开关共进退，且能同时挂本地@Tool与MCP ToolProvider。
     *
     * 另一个必须程序化装配的原因：工具循环保护参数（maxToolCallingRoundTrips等）
     * 在@AiService注解上根本没有对应属性，注解式装配拿不到这些控制权。
     */
    @Bean
    public AgenticRagAssistant agenticRagAssistant(
            @Qualifier("qwenStreamingChatModel") StreamingChatModel qwenStreamingChatModel,
            @Qualifier("chatMemoryProviderXiaozhi") ChatMemoryProvider chatMemoryProvider,
            AppointmentTools appointmentTools,
            KnowledgeSearchTool knowledgeSearchTool,
            McpToolProvider mcpToolProvider,
            ToolLoopGuard toolLoopGuard,
            @Value("${rag.tool.max-round-trips:5}") int maxRoundTrips,
            @Value("${rag.tool.max-sequential-invocations:3}") int maxSequentialInvocations) {

        return AiServices.builder(AgenticRagAssistant.class)
                .streamingChatModel(qwenStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                // 本地工具：知识库检索（走HybridRetrievalService，与固定RAG同一套检索能力）、
                // 预约挂号（MySQL）
                .tools(appointmentTools, knowledgeSearchTool)
                // MCP工具：filesystem server提供的list/read文件能力
                .toolProvider(mcpToolProvider)
                // ===== 工具循环保护 =====
                // 本助手挂了本地工具 + MCP filesystem的十来个工具，而queryDepartment目前
                // 是恒返回true的桩实现（AppointmentTools），模型拿不到有效信息会反复重试，
                // 没有任何天然刹车。
                // maxToolCallingRoundTrips：硬刹车，超过就带着已有信息收口
                // maxSequentialToolsInvocations：限制单轮内的连续工具调用，避免一次爆炸
                // beforeToolExecution：只观测不拦截，把循环暴露到日志便于定位根因
                .maxToolCallingRoundTrips(maxRoundTrips)
                .maxSequentialToolsInvocations(maxSequentialInvocations)
                .beforeToolExecution(toolLoopGuard)
                .build();
    }
}
