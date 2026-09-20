package com.mayaping.ai.langchain4j.rag.tools;

import dev.langchain4j.service.tool.BeforeToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工具循环观测器：把"LLM反复调用同一个工具"暴露到日志里。
 *
 * 为什么只观测不拦截：langchain4j 的 beforeToolExecution 是 Consumer，没有否决能力——
 * 只能看一眼，不能取消这次调用。真正的硬刹车由 AiServices 的
 * maxToolCallingRoundTrips 与 maxSequentialToolsInvocations 负责（见 AgenticRagConfig）。
 *
 * 两者分工：
 *   - maxToolCallingRoundTrips：兜底刹车。超了直接结束，但你不知道"为什么超"
 *   - 本类：记录是哪个工具、什么参数、重复了几次。让你能定位根因——
 *     是提示词没说清？是工具返回值没有信息量导致模型反复重试？还是工具本身有bug？
 *
 * 重点盯两种循环：
 *   - KnowledgeSearchTool 用几乎相同的词反复查 → 首次检索结果没答上问题
 *   - queryDepartment 被反复调用 → 它目前是恒返回 true 的桩实现（AppointmentTools），
 *     模型拿不到有效信息会一直试
 */
@Component
public class ToolLoopGuard implements Consumer<BeforeToolExecution> {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopGuard.class);

    /** 单个invocation内保留的调用记录上限，防止长会话撑爆内存 */
    private static final int MAX_CALLS_PER_INVOCATION = 50;
    /** 同时跟踪的invocation数上限，超出按LRU淘汰 */
    private static final int MAX_TRACKED_INVOCATIONS = 512;
    /** 写日志时参数截断长度，避免把整个检索词/文件内容打出来 */
    private static final int MAX_ARGUMENT_LOG_CHARS = 200;

    private final int repeatWarnThreshold;

    /**
     * 按invocation隔离的调用记录。用访问序LinkedHashMap做LRU，避免只增不减。
     * 注意这里不能用@ToolMemoryId那套：memoryId是整个会话的，跨轮次累积会导致误报。
     */
    private final Map<UUID, Deque<String>> callsByInvocation =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Deque<String>> eldest) {
                    return size() > MAX_TRACKED_INVOCATIONS;
                }
            });

    public ToolLoopGuard(@Value("${rag.tool.repeat-warn-threshold:3}") int repeatWarnThreshold) {
        this.repeatWarnThreshold = repeatWarnThreshold;
    }

    @Override
    public void accept(BeforeToolExecution event) {
        String callKey = event.request().name() + " | " + abbreviate(event.request().arguments());
        UUID invocationId = event.invocationContext().invocationId();

        Deque<String> calls = callsByInvocation.computeIfAbsent(invocationId, id -> new ArrayDeque<>());

        int repeat;
        List<String> snapshot;
        synchronized (calls) {
            calls.addLast(callKey);
            while (calls.size() > MAX_CALLS_PER_INVOCATION) {
                calls.removeFirst();
            }
            repeat = Collections.frequency(calls, callKey);
            snapshot = new ArrayList<>(calls);
        }

        if (repeat >= repeatWarnThreshold) {
            log.warn("疑似工具循环：本次会话已第{}次以相同参数调用 [{}]。invocationId={}，已知调用序列={}",
                    repeat, event.request().name(), invocationId, snapshot);
        } else {
            log.debug("工具调用：{}（本次会话第{}次）", callKey, repeat);
        }
    }

    private static String abbreviate(String arguments) {
        if (arguments == null) {
            return "null";
        }
        return arguments.length() <= MAX_ARGUMENT_LOG_CHARS
                ? arguments
                : arguments.substring(0, MAX_ARGUMENT_LOG_CHARS) + "...";
    }
}
