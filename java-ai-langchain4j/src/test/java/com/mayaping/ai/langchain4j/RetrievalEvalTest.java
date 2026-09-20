package com.mayaping.ai.langchain4j;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 检索质量评测：对完整在线链路（查询扩展→混合检索→RRF→父块扩展→rerank）算Recall
 *
 * 前置：先跑IngestionTest建好索引
 * 对比方法：将application.properties中rag.retrieval.rerank-enabled设为false再跑一遍，可对比纯RRF效果
 */
@SpringBootTest
public class RetrievalEvalTest {

    /**
     * 评测集：问题 → 期望命中的文档（docId即文件名）
     */
    private static final List<String[]> EVAL_CASES = List.of(
            new String[]{"医院地址在哪里，坐地铁怎么走？", "医院信息.md"},
            new String[]{"门诊的开放时间是什么时候？", "医院信息.md"},
            new String[]{"神经内科在门诊楼几楼？", "科室信息.md"},
            new String[]{"孩子发烧应该挂什么科？", "科室信息.md"},
            new String[]{"脑梗塞的前兆症状有哪些？", "神经内科.md"},
            new String[]{"溶栓治疗的时间窗是多久？", "神经内科.md"},
            new String[]{"做脑电图检查要注意什么？", "神经内科.md"},
            new String[]{"体检之前需要做什么准备？", "体检中心.md"},
            new String[]{"高血压患者日常要注意什么？", "体检中心.md"},
            new String[]{"流感疫苗什么时候打比较好？", "体检中心.md"}
    );

    @Autowired
    private RetrievalAugmentor retrievalAugmentor;

    @Test
    public void testRecall() {
        int hit = 0;
        int total = EVAL_CASES.size();

        System.out.println("======== 检索质量评测（完整链路） ========");
        for (String[] evalCase : EVAL_CASES) {
            String question = evalCase[0];
            String expectedDocId = evalCase[1];

            AugmentationResult result = retrievalAugmentor.augment(new AugmentationRequest(
                    UserMessage.from(question), Metadata.from(UserMessage.from(question), "eval", List.of())));

            List<String> hitDocIds = result.contents().stream()
                    .map(Content::textSegment)
                    .map(segment -> segment.metadata().getString("docId"))
                    .toList();

            boolean isHit = hitDocIds.contains(expectedDocId);
            if (isHit) {
                hit++;
            }
            System.out.printf("%s  问题：%s%n   期望：%s，实际命中：%s%n",
                    isHit ? "✓" : "✗", question, expectedDocId, hitDocIds);
        }

        double recall = (double) hit / total;
        System.out.println("=========================================");
        System.out.printf("Recall = %d/%d = %.1f%%%n", hit, total, recall * 100);
    }
}
