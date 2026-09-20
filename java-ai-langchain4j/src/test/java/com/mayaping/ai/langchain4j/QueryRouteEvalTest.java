package com.mayaping.ai.langchain4j;

import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter.Route;
import com.mayaping.ai.langchain4j.rag.router.QueryComplexityRouter.RouteDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 路由质量评测：对"问题复杂度判断"算准确率，并统计规则命中率
 *
 * 前置：需要DASH_SCOPE_API_KEY（规则判不出的用例会真实调一次LLM分类器）
 *
 * 调参方法看输出的"依据"列：
 * - 判错且依据是"规则·xxx"→ 去调QueryComplexityRouter里的信号词表，或调
 *   application.properties的rag.router.simple-max-chars / complex-min-chars阈值
 * - 判错且依据是"LLM·xxx"→ 去调QueryComplexityRouter.CLASSIFY_PROMPT
 * - 规则命中率太低说明词表覆盖不足，大量流量白白多付一次LLM往返
 */
@SpringBootTest
public class QueryRouteEvalTest {

    private record EvalCase(String question, Route expected) {}

    /**
     * 评测集：问题 → 期望路由。前8条应由简单规则判定，中8条应由复杂规则判定，末3条落到LLM兜底
     */
    private static final List<EvalCase> EVAL_CASES = List.of(
            // —— 期望走固定RAG管线 ——
            new EvalCase("你好", Route.SIMPLE_RAG),
            new EvalCase("医院几点上班？", Route.SIMPLE_RAG),
            new EvalCase("我想挂号", Route.SIMPLE_RAG),
            new EvalCase("帮我取消明天上午的预约", Route.SIMPLE_RAG),
            new EvalCase("神经内科在门诊楼几楼？", Route.SIMPLE_RAG),
            new EvalCase("脑梗塞的前兆症状有哪些？", Route.SIMPLE_RAG),
            new EvalCase("做脑电图检查之前需要空腹吗", Route.SIMPLE_RAG),
            new EvalCase("流感疫苗什么时候打好", Route.SIMPLE_RAG),

            // —— 期望走Agentic RAG ——
            new EvalCase("神经内科和心内科都看什么病？", Route.AGENTIC_RAG),
            new EvalCase("请分别介绍一下体检中心和神经内科", Route.AGENTIC_RAG),
            new EvalCase("脑梗和脑出血有什么区别？", Route.AGENTIC_RAG),
            new EvalCase("帮我读一下体检中心那个文档的原文", Route.AGENTIC_RAG),
            new EvalCase("知识库里都有哪些文件？", Route.AGENTIC_RAG),
            new EvalCase("总结一下医院的就诊须知", Route.AGENTIC_RAG),
            new EvalCase("我先做个体检，再根据结果挂对应的科室，该怎么安排？", Route.AGENTIC_RAG),
            new EvalCase("挂号需要带什么？体检需要空腹吗？", Route.AGENTIC_RAG),

            // —— 规则判不出的中间地带，交给LLM分类器 ——
            new EvalCase("失眠已经持续一个月了应该去看哪个科室比较合适", Route.SIMPLE_RAG),
            new EvalCase("宝宝最近总是半夜发烧咳嗽应该去看什么科室呢", Route.SIMPLE_RAG),
            new EvalCase("我要做一个全身体检同时还想咨询一下失眠的治疗方案", Route.AGENTIC_RAG)
    );

    @Autowired
    private QueryComplexityRouter queryComplexityRouter;

    @Test
    public void testRouteAccuracy() {
        int hit = 0;
        int byRule = 0;
        int total = EVAL_CASES.size();

        System.out.println("======== 复杂度路由评测 ========");
        for (EvalCase evalCase : EVAL_CASES) {
            long start = System.nanoTime();
            RouteDecision decision = queryComplexityRouter.decide(evalCase.question());
            long costMs = (System.nanoTime() - start) / 1_000_000;

            boolean isHit = decision.route() == evalCase.expected();
            if (isHit) {
                hit++;
            }
            if (decision.reason().startsWith("规则")) {
                byRule++;
            }
            System.out.printf("%s  %-26s 期望：%-11s 实际：%-11s 依据：%-22s 耗时：%dms%n",
                    isHit ? "✓" : "✗", evalCase.question(), evalCase.expected(), decision.route(),
                    decision.reason(), costMs);
        }

        System.out.println("================================");
        System.out.printf("准确率   = %d/%d = %.1f%%%n", hit, total, (double) hit / total * 100);
        System.out.printf("规则命中 = %d/%d = %.1f%%（其余走LLM兜底）%n",
                byRule, total, (double) byRule / total * 100);
    }
}
