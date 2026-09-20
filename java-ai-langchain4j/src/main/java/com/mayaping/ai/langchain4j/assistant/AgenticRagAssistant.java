package com.mayaping.ai.langchain4j.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * Agentic RAG助手：不挂固定检索管线，检索/文件访问全部作为工具由LLM自主调度
 * （与XiaozhiAgent的固定RAG管线形成两种模式对比）
 */
public interface AgenticRagAssistant {

    @SystemMessage(fromResource = "agentic-prompt-template.txt")
    Flux<String> chat(@MemoryId Long memoryId, @UserMessage String userMessage);
}
