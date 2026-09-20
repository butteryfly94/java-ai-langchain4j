package com.mayaping.ai.langchain4j.rag.citation;

import com.mayaping.ai.langchain4j.rag.aggregator.RrfRerankAggregator;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 聚合器装饰器：把ReRank后的最终父块登记为引用来源，其余行为原样委托。
 *
 * 为什么记"聚合器之后"而不是"检索器之后"：
 * 检索器产出的是child子块（300字碎片），而真正进入prompt、真正支撑答案的是
 * 聚合器回查出来的**父块**。记错一层，用户看到的出处会和答案对不上。
 *
 * 为什么能在这里拿到memoryId：
 * 聚合器的入参是 Map&lt;Query, ...&gt;，而 Query 携带 metadata，
 * metadata.chatMemoryId() 就是本次调用的会话ID。不需要额外透传。
 */
public class RecordingContentAggregator implements ContentAggregator {

    private static final Logger log = LoggerFactory.getLogger(RecordingContentAggregator.class);

    private final RrfRerankAggregator delegate;
    private final CitationRecorder citationRecorder;

    public RecordingContentAggregator(RrfRerankAggregator delegate, CitationRecorder citationRecorder) {
        this.delegate = delegate;
        this.citationRecorder = citationRecorder;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> results = delegate.aggregate(queryToContents);
        record(queryToContents, results);
        return results;
    }

    @Override
    public CompletableFuture<List<Content>> aggregateAsync(
            Map<Query, Collection<List<Content>>> queryToContents) {
        return delegate.aggregateAsync(queryToContents)
                .thenApply(results -> {
                    record(queryToContents, results);
                    return results;
                });
    }

    private void record(Map<Query, Collection<List<Content>>> queryToContents, List<Content> results) {
        Object memoryId = resolveMemoryId(queryToContents);
        if (memoryId == null) {
            log.debug("本次聚合未携带chatMemoryId，跳过引用登记（评测/工具直调场景属正常）");
            return;
        }
        citationRecorder.record(memoryId, results);
    }

    private Object resolveMemoryId(Map<Query, Collection<List<Content>>> queryToContents) {
        return queryToContents.keySet().stream()
                .map(Query::metadata)
                .filter(Objects::nonNull)
                .map(dev.langchain4j.rag.query.Metadata::chatMemoryId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
