package com.mayaping.ai.langchain4j.rag.tools;

import com.mayaping.ai.langchain4j.rag.citation.CitationRecorder;
import com.mayaping.ai.langchain4j.rag.retrieval.HybridRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agentic RAG：知识库检索即工具
 *
 * 与固定RAG管线的区别在"谁决定检索"：不预先检索，而是把"查知识库"暴露为一个@Tool，
 * 由LLM在对话中自主决定：是否需要查、用什么关键词查、查几次、结果够不够。
 * 适合多跳问题（如"神经内科和心内科都看什么病，哪个适合我？"需要两次不同检索）
 *
 * 但**检索能力本身与固定链路完全一致**——底层复用 HybridRetrievalService
 * （kNN + BM25双路召回 → RRF融合 → 父块扩展 → rerank重排），而不是裸向量检索。
 *
 * 这一点是刻意的：两条链路唯一该有差异的是编排方式，不是检索质量。若Agentic用裸
 * embeddingStore检索（只有kNN、只返回child碎片、无RRF无rerank），而固定链路用完整
 * 管线，那么任何"固定 vs Agentic"的评测里都有两个变量同时变化，结论不可解释。
 *
 * 这里同样刻意不经过查询扩展——LLM生成检索关键词这一步本身就是query transformation，
 * 再套一层ExpandingQueryTransformer是重复劳动，且每次工具调用要多付一次LLM往返。
 */
@Component
public class KnowledgeSearchTool {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryDedupCache queryDedupCache;

    @Autowired
    private CitationRecorder citationRecorder;

    @Tool(name = "知识库检索",
            value = "根据查询内容从医院知识库中检索相关信息，返回最相关的知识片段。" +
                    "当用户询问医院介绍、科室分布、疾病症状、就医流程、挂号预约规则、检查注意事项、体检疫苗等信息时调用此工具。" +
                    "如果首次检索结果不够回答用户问题，可以换一个角度重新组织查询关键词再次检索。" +
                    "请勿使用完全相同的查询词重复检索，那不会得到新的信息。")
    public String searchKnowledge(
            @P(value = "查询内容，例如：科室名称、症状描述、就医流程问题等") String query,
            @ToolMemoryId Long memoryId) {

        // 同一轮里用相同词重复检索不会带来新信息，只会成倍放大ES与rerank开销。
        // 命中时返回真实的上次结果而非一句"查询重复"——后者会让模型失去数据、更用力地重试。
        String cached = queryDedupCache.getFreshResult(memoryId, query);
        if (cached != null) {
            return "【该查询刚刚已执行过，以下是上次的检索结果。请直接基于它作答；"
                    + "若信息仍不足，请换一个不同角度的关键词重新检索】\n\n" + cached;
        }

        List<Content> contents = hybridRetrievalService.retrieve(query);

        String result;
        if (contents.isEmpty()) {
            result = "知识库中未检索到相关信息，请基于自身知识回答并提示用户信息可能不准确";
        } else {
            // 登记引用来源，由controller在流末尾统一输出给前端。
            // memoryId由 @ToolMemoryId 注入——工具方法是单例、会被并发调用，
            // 不能用成员变量暂存会话身份
            citationRecorder.record(memoryId, contents);

            // 拼接检索结果：来源 + 页码 + 片段内容，供LLM判断信息是否充分并如实标注出处
            result = contents.stream()
                    .map(this::formatContent)
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        queryDedupCache.record(memoryId, query, result);
        return result;
    }

    /**
     * 格式化单条检索结果。
     *
     * 来源与页码取自索引元数据，是引用溯源的依据，必须原样带给LLM——
     * 让模型自己回忆"这段话出自哪份文档"是幻觉的主要来源之一。
     */
    private String formatContent(Content content) {
        Metadata metadata = content.textSegment().metadata();

        String source = metadata.getString("source");
        if (source == null || source.isBlank()) {
            source = metadata.getString("docId");
        }
        if (source == null || source.isBlank()) {
            source = "未知";
        }

        Integer page = metadata.getInteger("page");
        String location = page == null ? source : source + " 第" + page + "页";

        return String.format("[来源：%s]%n%s", location, content.textSegment().text());
    }
}
