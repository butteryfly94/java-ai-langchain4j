package com.mayaping.ai.langchain4j;

import com.mayaping.ai.langchain4j.rag.ingestion.IngestionStats;
import com.mayaping.ai.langchain4j.rag.ingestion.KnowledgeIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 离线索引管线测试：跑全量父子分块索引
 * 前置：docker compose up -d 启动ES，DASH_SCOPE_API_KEY环境变量已设置
 */
@SpringBootTest
public class IngestionTest {

    @Autowired
    private KnowledgeIngestionService knowledgeIngestionService;

    @Test
    public void testIngestAll() throws Exception {
        IngestionStats stats = knowledgeIngestionService.ingestAll();
        System.out.println("索引统计：");
        stats.asMap().forEach((key, value) -> System.out.println("  " + key + " = " + value));

        //基本合理性断言：有文档、有父块、子块数多于父块数
        assert stats.get("documents") > 0 : "知识文档数应大于0";
        assert stats.get("parents") > 0 : "父块数应大于0";
        assert stats.get("children") >= stats.get("parents") : "子块数应不少于父块数";
    }
}
