package com.mayaping.ai.langchain4j.controller;

import com.mayaping.ai.langchain4j.rag.ingestion.IngestionStats;
import com.mayaping.ai.langchain4j.rag.ingestion.KnowledgeIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Tag(name = "知识库索引")
@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    @Autowired
    private KnowledgeIngestionService knowledgeIngestionService;

    @Operation(summary = "全量重建知识库索引（父子分块）")
    @PostMapping
    public Map<String, Integer> ingest() throws IOException {
        IngestionStats stats = knowledgeIngestionService.ingestAll();
        return stats.asMap();
    }
}
