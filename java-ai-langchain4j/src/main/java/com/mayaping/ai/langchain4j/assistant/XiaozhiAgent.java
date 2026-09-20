package com.mayaping.ai.langchain4j.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 小智固定RAG助手：挂载完整检索管线
 * （查询扩展 → kNN/BM25双路召回 → RRF融合 → 父块扩展 → rerank重排）
 *
 * 装配在 {@link com.mayaping.ai.langchain4j.config.XiaozhiAgentConfig} 中以编程方式完成，
 * 而不是这里的 @AiService 注解——因为循环保护参数（maxToolCallingRoundTrips、
 * maxSequentialToolsInvocations、beforeToolExecution）在 @AiService 注解上没有任何
 * 对应属性，注解式装配拿不到这些控制权。两个助手因此统一为编程式装配，风格一致。
 *
 * 方法上的 @SystemMessage 在编程式装配下依然生效（langchain4j 从接口方法上读注解），
 * 所以提示词仍然由 fromResource 声明，不必挪到配置类里。
 */
public interface XiaozhiAgent {

    @SystemMessage(fromResource = "zhaozhi-prompt-template.txt")
    Flux<String> chat(@MemoryId Long memoryId, @UserMessage String userMessage);
}
