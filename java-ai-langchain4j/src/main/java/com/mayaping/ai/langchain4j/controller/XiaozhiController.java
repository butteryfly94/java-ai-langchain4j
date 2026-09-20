package com.mayaping.ai.langchain4j.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayaping.ai.langchain4j.assistant.AgenticRagAssistant;
import com.mayaping.ai.langchain4j.assistant.XiaozhiAgent;
import com.mayaping.ai.langchain4j.bean.ChatForm;
import com.mayaping.ai.langchain4j.rag.citation.CitationRecorder;
import com.mayaping.ai.langchain4j.rag.citation.SourceRef;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter.Route;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Tag(name = "硅谷小智")
@RestController
@RequestMapping("/api/xiaozhi")
public class XiaozhiController {

    /**
     * 流式响应中分隔答案正文与引用来源的标记，前端按它切分。
     * 选择在流末尾追加而不是改造响应格式（如换SSE事件），
     * 是为了不影响现有的 text/stream 增量读取方式。
     */
    private static final String SOURCES_MARKER = "\n\n---SOURCES---\n";

    private static final Logger log = LoggerFactory.getLogger(XiaozhiController.class);

    @Autowired
    private XiaozhiAgent xiaozhiAgent;

    //MCP关闭时（rag.mcp.enabled=false）该bean不存在
    @Autowired(required = false)
    private AgenticRagAssistant agenticRagAssistant;

    @Autowired
    private QueryComplexityRouter queryComplexityRouter;

    @Autowired
    private CitationRecorder citationRecorder;

    @Autowired
    private ObjectMapper objectMapper;

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
                    Flux<String> answer;
                    if (agenticRagAssistant == null) {
                        answer = xiaozhiAgent.chat(chatForm.getMemoryId(), chatForm.getMessage());
                    } else {
                        answer = queryComplexityRouter.route(chatForm.getMessage()) == Route.AGENTIC_RAG
                                ? agenticRagAssistant.chat(chatForm.getMemoryId(), chatForm.getMessage())
                                : xiaozhiAgent.chat(chatForm.getMemoryId(), chatForm.getMessage());
                    }
                    return withSources(answer, chatForm.getMemoryId());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "对话（强制Agentic RAG：检索/文件访问作为工具由LLM自主调度，含MCP）")
    @PostMapping(value = "/agentic-chat", produces = "text/stream;charset=utf-8")
    public Flux<String> agenticChat(@RequestBody ChatForm chatForm){
        if (agenticRagAssistant == null) {
            return Flux.just("Agentic RAG未启用（rag.mcp.enabled=false）");
        }
        return withSources(
                agenticRagAssistant.chat(chatForm.getMemoryId(), chatForm.getMessage()),
                chatForm.getMemoryId());
    }

    /**
     * 在答案流末尾追加引用来源。
     *
     * 必须用 concatWith + Flux.defer，不能直接调 drain()：
     * defer 的 supplier 在第二个发布者**被订阅时**才执行，也就是答案流结束之后。
     * 若直接调用，drain 会在方法返回前立刻执行，那时检索还没发生，拿到的永远是空列表。
     */
    private Flux<String> withSources(Flux<String> answer, Long memoryId) {
        return answer.concatWith(Flux.defer(() -> sourcesTail(memoryId)));
    }

    private Flux<String> sourcesTail(Long memoryId) {
        List<SourceRef> sources = citationRecorder.drain(memoryId);
        if (sources.isEmpty()) {
            return Flux.empty();
        }
        try {
            return Flux.just(SOURCES_MARKER + objectMapper.writeValueAsString(sources));
        } catch (JsonProcessingException e) {
            // 引用输出失败不该影响已经流式返回的答案正文
            log.warn("引用来源序列化失败，本次不返回来源：{}", e.getMessage());
            return Flux.empty();
        }
    }
}
