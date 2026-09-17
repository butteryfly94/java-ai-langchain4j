package com.mayaping.ai.langchain4j.rag.router;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 问题复杂度路由器：决定一句话走固定RAG管线还是Agentic RAG
 *
 * 用户问题
 *    ↓
 * ① 复杂信号规则（多问句/超长/并列多主题/对比择优/要原文/多步推理/要汇总）
 *      命中 → AGENTIC_RAG                                   0ms
 *    ↓ 未命中
 * ② 简单信号规则（挂号业务/寒暄/短句）
 *      命中 → SIMPLE_RAG                                    0ms
 *    ↓ 未命中（中间地带）
 * ③ LLM分类器兜底（qwen-turbo，只吐SIMPLE或COMPLEX）        约300ms
 *      失败 → 降级SIMPLE_RAG
 *
 * 设计要点：
 * - 规则先行是为了把绝大多数流量挡在LLM调用之前。每轮对话都多付一次分类往返，
 *   对"医院几点上班"这种问题是纯粹的浪费
 * - 复杂信号先于简单信号判定，顺序不能反：这样"对比一下这两个科室，然后帮我挂号"
 *   会走Agentic，而不会被"挂号"这个简单信号提前吞掉
 * - 挂号业务刻意归入简单信号：两个助手都挂了appointmentTools，但zhaozhi-prompt-template.txt
 *   写死了"必须先拿到姓名/身份证号/科室/日期/时段"的完整校验规则，
 *   agentic-prompt-template.txt只有一句带过，挂号流程走固定管线明显更可靠
 * - 只看当前这一句，不读对话历史：判定是纯函数，无外部依赖。代价是"那第二个呢？"
 *   这类指代型追问会被判成简单问题
 * - 判定失败一律降级为SIMPLE_RAG：分类不该让整个对话失败，普通RAG是更快更稳的默认值
 */
@Service
public class QueryComplexityRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryComplexityRouter.class);

    public enum Route {
        /** 固定RAG管线：查询扩展→kNN/BM25双路召回→RRF→父块扩展→rerank，一次检索一次回答 */
        SIMPLE_RAG,
        /** Agentic RAG：检索与文件读取作为工具由LLM自主调度，能多跳、能读文档原文 */
        AGENTIC_RAG
    }

    /** 判定结果。除路由外带上判定依据，便于日志排查与评测时调参 */
    public record RouteDecision(Route route, String reason) {}

    private record Signal(String name, Pattern pattern) {}

    /**
     * 复杂信号：命中任一即认为一次检索答不好，需要LLM自主多轮调度
     */
    private static final List<Signal> COMPLEX_SIGNALS = List.of(
            // "神经内科和心内科都看什么病" / "请分别介绍这两个科室"
            new Signal("并列多主题", Pattern.compile("[和与跟].{0,20}(都|分别|各自)|分别|各自")),
            new Signal("对比择优", Pattern.compile("对比|区别|差异|相比|哪个更|哪个好|哪个比较|哪种更|哪种好|有什么不同")),
            new Signal("要文档原文", Pattern.compile("原文|全文|文档|文件|附件|完整内容|列出所有|哪些文件")),
            // 刻意不收"步骤/流程"：像"体检流程是什么"其实一次检索就够，收进来误判率太高
            new Signal("多步推理", Pattern.compile("先.{0,15}再|然后|接着|一步步")),
            new Signal("要汇总整理", Pattern.compile("总结|汇总|梳理|整理一下|归纳"))
    );

    /**
     * 简单信号：命中任一即认为固定管线足够，且往往走固定管线效果更好
     */
    private static final List<Signal> SIMPLE_SIGNALS = List.of(
            new Signal("挂号业务", Pattern.compile("挂号|预约|号源|退号")),
            new Signal("寒暄", Pattern.compile("^(你好|您好|hi|hello|嗨)|谢谢|多谢|再见|拜拜|你是谁|你叫什么",
                    Pattern.CASE_INSENSITIVE))
    );

    private static final String CLASSIFY_PROMPT = """
            你是一个问题复杂度分类器，服务于一个医院智能客服的RAG系统。
            判断用户问题是否需要"多轮检索或读取知识文档原文"才能答好。

            判为 COMPLEX：
            - 涉及多个科室/疾病/主题，需要分别检索后综合
            - 需要对比、排序、择优
            - 需要查看知识库文档原文，或列出有哪些文档
            - 需要多步推理，一次检索的结果不足以回答

            判为 SIMPLE：
            - 单一事实查询，检索一次即可回答
            - 寒暄、问候、致谢
            - 挂号、预约、取消等单一业务操作

            只输出一个单词：SIMPLE 或 COMPLEX。不要解释，不要标点。

            用户问题：%s
            """;

    private final ChatModel routerChatModel;

    private final boolean enabled;
    private final boolean llmFallbackEnabled;
    private final int simpleMaxChars;
    private final int complexMinChars;

    public QueryComplexityRouter(@Qualifier("routerChatModel") ChatModel routerChatModel,
                                 @Value("${rag.router.enabled:true}") boolean enabled,
                                 @Value("${rag.router.llm-fallback:true}") boolean llmFallbackEnabled,
                                 @Value("${rag.router.simple-max-chars:15}") int simpleMaxChars,
                                 @Value("${rag.router.complex-min-chars:60}") int complexMinChars) {
        this.routerChatModel = routerChatModel;
        this.enabled = enabled;
        this.llmFallbackEnabled = llmFallbackEnabled;
        this.simpleMaxChars = simpleMaxChars;
        this.complexMinChars = complexMinChars;
    }

    /**
     * 业务侧入口：只要路由结果
     */
    public Route route(String userMessage) {
        return decide(userMessage).route();
    }

    /**
     * 带判定依据的完整判定，供评测与排查使用
     */
    public RouteDecision decide(String userMessage) {
        if (!enabled) {
            return new RouteDecision(Route.SIMPLE_RAG, "路由已关闭");
        }
        if (userMessage == null || userMessage.isBlank()) {
            return new RouteDecision(Route.SIMPLE_RAG, "空问题");
        }

        RouteDecision decision = judge(userMessage.trim());
        log.info("复杂度路由 → {}（依据：{}）｜问题：{}", decision.route(), decision.reason(), userMessage);
        return decision;
    }

    /**
     * 三段式判定，短路返回
     */
    private RouteDecision judge(String question) {
        // ===== ① 复杂信号 =====
        if (countQuestionMarks(question) >= 2) {
            return new RouteDecision(Route.AGENTIC_RAG, "规则·多问句");
        }
        if (question.length() > complexMinChars) {
            return new RouteDecision(Route.AGENTIC_RAG, "规则·超长问题(>" + complexMinChars + "字)");
        }
        for (Signal signal : COMPLEX_SIGNALS) {
            if (signal.pattern().matcher(question).find()) {
                return new RouteDecision(Route.AGENTIC_RAG, "规则·" + signal.name());
            }
        }

        // ===== ② 简单信号 =====
        for (Signal signal : SIMPLE_SIGNALS) {
            if (signal.pattern().matcher(question).find()) {
                return new RouteDecision(Route.SIMPLE_RAG, "规则·" + signal.name());
            }
        }
        if (question.length() <= simpleMaxChars) {
            return new RouteDecision(Route.SIMPLE_RAG, "规则·短句(<=" + simpleMaxChars + "字)");
        }

        // ===== ③ 中间地带交给LLM =====
        if (!llmFallbackEnabled) {
            return new RouteDecision(Route.SIMPLE_RAG, "规则未命中且LLM兜底已关闭");
        }
        return classifyByLlm(question);
    }

    /**
     * LLM二分类兜底。失败降级为SIMPLE_RAG，不向上抛——分类失败不该让整个对话失败
     */
    private RouteDecision classifyByLlm(String question) {
        try {
            String answer = routerChatModel.chat(CLASSIFY_PROMPT.formatted(question));
            if (answer == null) {
                return new RouteDecision(Route.SIMPLE_RAG, "LLM·空响应降级");
            }
            boolean complex = answer.toUpperCase().contains("COMPLEX");
            return new RouteDecision(complex ? Route.AGENTIC_RAG : Route.SIMPLE_RAG, "LLM·" + answer.trim());
        } catch (Exception e) {
            log.warn("复杂度分类失败，降级为普通RAG：{}", e.getMessage());
            return new RouteDecision(Route.SIMPLE_RAG, "LLM分类失败降级");
        }
    }

    /**
     * 中英文问号合计。一句话里两个以上问号通常意味着问了多件事
     */
    private static int countQuestionMarks(String question) {
        int count = 0;
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (c == '?' || c == '？') {
                count++;
            }
        }
        return count;
    }
}
