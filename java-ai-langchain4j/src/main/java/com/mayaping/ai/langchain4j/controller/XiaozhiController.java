package com.mayaping.ai.langchain4j.controller;

import com.mayaping.ai.langchain4j.assistant.AgenticRagAssistant;
import com.mayaping.ai.langchain4j.assistant.XiaozhiAgent;
import com.mayaping.ai.langchain4j.bean.ChatForm;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter.Route;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Tag(name = "硅谷小智")
@RestController
@RequestMapping("/api/xiaozhi")
public class XiaozhiController {

    @Autowired
    private XiaozhiAgent xiaozhiAgent;

    //MCP关闭时（rag.mcp.enabled=false）该bean不存在
    @Autowired(required = false)
    private AgenticRagAssistant agenticRagAssistant;

    @Autowired
    private QueryComplexityRouter queryComplexityRouter;

    /**
     * 唯一对话入口：先判断问题复杂度，再决定走哪条链路。
     * 简单问题走固定RAG管线（一次检索一次回答，快），复杂问题走Agentic RAG（LLM自主多轮调度，能多跳、能读原文）。
     *
     * 两个助手共用chatMemoryProviderXiaozhi，同一memoryId落到同一份MongoDB记忆，
     * 所以逐轮切换路由不丢上下文——MessageWindowChatMemory对SystemMessage是替换语义，
     * 两套人设提示词随路由互换，而User/AI消息历史完整保留。
     */
    @Operation(summary = "对话（自动路由：按问题复杂度选普通RAG或Agentic RAG）")
    @PostMapping(value = "/chat", produces = "text/stream;charset=utf-8")
    public Flux<String> chat(@RequestBody ChatForm chatForm){
        //项目跑的是Spring MVC servlet栈+Flux流式返回，复杂度判定可能要调一次LLM，
        //直接同步调会阻塞Tomcat请求线程，故用defer推迟到订阅时、再subscribeOn挪到弹性线程池
        return Flux.defer(() -> {
                    //Agentic RAG未启用（rag.mcp.enabled=false）时该bean为null，无条件降级
                    if (agenticRagAssistant == null) {
                        return xiaozhiAgent.chat(chatForm.getMemoryId(), chatForm.getMessage());
                    }
                    return queryComplexityRouter.route(chatForm.getMessage()) == Route.AGENTIC_RAG
                            ? agenticRagAssistant.chat(chatForm.getMemoryId(), chatForm.getMessage())
                            : xiaozhiAgent.chat(chatForm.getMemoryId(), chatForm.getMessage());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "对话（强制Agentic RAG：检索/文件访问作为工具由LLM自主调度，含MCP）")
    @PostMapping(value = "/agentic-chat", produces = "text/stream;charset=utf-8")
    public Flux<String> agenticChat(@RequestBody ChatForm chatForm){
        if (agenticRagAssistant == null) {
            return Flux.just("Agentic RAG未启用（rag.mcp.enabled=false）");
        }
        return agenticRagAssistant.chat(chatForm.getMemoryId(), chatForm.getMessage());
    }
}
