package com.mayaping.ai.langchain4j.rag.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agentic RAG：知识库检索即工具
 *
 * 与固定RAG管线的区别：不预先检索，而是把"查知识库"暴露为一个@Tool，
 * 由LLM在对话中自主决定：是否需要查、用什么关键词查、查几次、结果够不够。
 * 适合多跳问题（如"神经内科和心内科都看什么病，哪个适合我？"需要两次不同检索）
 */
@Component
public class KnowledgeSearchTool {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Tool(name = "知识库检索",
            value = "根据查询内容从医院知识库中检索相关信息，返回最相关的知识片段。" +
                    "当用户询问医院介绍、科室分布、疾病症状、就医流程、挂号预约规则、检查注意事项、体检疫苗等信息时调用此工具。" +
                    "如果首次检索结果不够回答用户问题，可以换一个角度重新组织查询关键词再次检索。")
    public String searchKnowledge(
            @P(value = "查询内容，例如：科室名称、症状描述、就医流程问题等") String query) {

        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        if (matches.isEmpty()) {
            return "知识库中未检索到相关信息，请基于自身知识回答并提示用户信息可能不准确";
        }

        // 拼接检索结果：来源文档 + 片段内容，供LLM判断信息是否充分
        return matches.stream()
                .map(match -> {
                    String docId = match.embedded().metadata().getString("docId");
                    return String.format("[来源：%s]\n%s", docId == null ? "未知" : docId, match.embedded().text());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
