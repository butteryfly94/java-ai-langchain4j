package com.mayaping.ai.langchain4j.controller;

import com.mayaping.ai.langchain4j.rag.ingestion.IngestionStats;
import com.mayaping.ai.langchain4j.rag.ingestion.KnowledgeIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "知识库索引")
@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    @Autowired
    private KnowledgeIngestionService knowledgeIngestionService;

    /**
     * 摄入知识库：默认增量，force=true 时全量重建。
     *
     * 响应里 warnings 不是可选装饰。PDF解析失败是静默的（PDFBox抽表格不报错、
     * MinerU的txt模式丢表格不报错、扫描件OCR崩了不报错），三者表现统一是
     * "文档里明明有，就是答不对"。warnings 是把这类失效暴露出来的唯一闸门。
     *
     * 返回 stats 的读法（增量模式下最容易看错的地方）：
     *   - documentsChanged / documentsSkipped：本次真正重灌了几份、跳过了几份
     *   - children：本次**真正重新向量化**的子块数（这决定了 embedding 花了多少钱）
     *   - childrenCarriedOver：从上一版索引直接搬运、**未产生 embedding 调用**的子块数
     *   - 索引总量应为 children + childrenCarriedOver
     *
     * 摄入期间检索不受影响：新内容写到新的物理索引上，写完才原子切换别名。
     */
    @Operation(summary = "摄入知识库（默认增量，force=true 全量重建）",
            description = "增量模式只重灌内容发生变化的文档，未变文档的向量从上一版索引直接复用；"
                    + "无论增量还是全量，都通过ES别名原子切换发布，摄入期间检索始终可用。"
                    + "返回 stats（计数）与 warnings（摄入校验告警，非空说明有文档可能未被正确解析）")
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @Parameter(description = "true=忽略指纹账本，全部文档重新切分并向量化")
            @RequestParam(name = "force", defaultValue = "false") boolean force) throws IOException {

        try {
            IngestionStats stats = knowledgeIngestionService.ingest(force);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("stats", stats.asMap());
            response.put("labels", stats.labelsAsMap());
            response.put("warnings", stats.warnings());
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            // 并发摄入：不是服务端错误，是调用时机冲突，用409明确表达。
            // 若返回200会让调用方以为摄入成功，而实际上什么都没做——这正是
            // 本项目一贯要避免的"静默失效"
            log.warn("摄入请求被拒绝：{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
