package com.mayaping.ai.langchain4j;

import com.mayaping.ai.langchain4j.rag.citation.CitationRecorder;
import com.mayaping.ai.langchain4j.rag.citation.SourceRef;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用来源收集器的单元测试。
 *
 * 不标 @SpringBootTest：纯内存逻辑，不需要 ES / LLM。
 * 重点验证 drain 的"取出即清"语义——这是多轮对话里最容易出错的地方：
 * 清早了来源丢失，清晚了上一轮的出处会挂到本轮答案上。
 */
public class CitationRecorderTest {

    private final CitationRecorder recorder = new CitationRecorder();

    private static Content content(String docId, String source, Integer page, String text) {
        Metadata metadata = new Metadata();
        if (docId != null) {
            metadata.put("docId", docId);
        }
        if (source != null) {
            metadata.put("source", source);
        }
        if (page != null) {
            metadata.put("page", page);
        }
        return Content.from(TextSegment.from(text, metadata));
    }

    @Test
    public void 记录后能取出文档与页码() {
        recorder.record(1L, List.of(
                content("神经内科.md", "神经内科.md", 3, "溶栓治疗的时间窗为4.5小时")));

        List<SourceRef> sources = recorder.drain(1L);

        assertEquals(1, sources.size());
        assertEquals("神经内科.md", sources.get(0).source());
        assertEquals(3, sources.get(0).page());
        assertTrue(sources.get(0).snippet().contains("溶栓"));
        assertEquals("神经内科.md 第3页", sources.get(0).location());
    }

    @Test
    public void drain后再次取出为空() {
        recorder.record(1L, List.of(content("a.md", "a.md", 1, "内容")));

        assertEquals(1, recorder.drain(1L).size());
        // 第二轮对话不能挂着第一轮的出处
        assertTrue(recorder.drain(1L).isEmpty(), "drain应清空，避免来源跨轮累积");
    }

    @Test
    public void 同一文档同一页去重() {
        // 一次检索常命中同一页的多个子块，不去重前端会列出重复来源
        recorder.record(1L, List.of(
                content("科室信息.md", "科室信息.md", 2, "片段一"),
                content("科室信息.md", "科室信息.md", 2, "片段二"),
                content("科室信息.md", "科室信息.md", 2, "片段三")));

        assertEquals(1, recorder.drain(1L).size());
    }

    @Test
    public void 多轮检索累积且保持顺序() {
        recorder.record(1L, List.of(content("a.md", "a.md", 1, "A")));
        recorder.record(1L, List.of(content("b.md", "b.md", 2, "B")));

        List<SourceRef> sources = recorder.drain(1L);

        assertEquals(2, sources.size());
        assertEquals("a.md", sources.get(0).source(), "应保持首次出现的顺序");
        assertEquals("b.md", sources.get(1).source());
    }

    @Test
    public void 不同会话互不干扰() {
        recorder.record(1L, List.of(content("a.md", "a.md", 1, "A")));
        recorder.record(2L, List.of(content("b.md", "b.md", 2, "B")));

        assertEquals("a.md", recorder.drain(1L).get(0).source());
        assertEquals("b.md", recorder.drain(2L).get(0).source());
    }

    @Test
    public void 会话ID为空时跳过而不是记到别人头上() {
        recorder.record(null, List.of(content("a.md", "a.md", 1, "A")));

        assertTrue(recorder.drain(null).isEmpty());
        assertTrue(recorder.drain(1L).isEmpty(), "memoryId为null时不能污染其他会话");
    }

    @Test
    public void 缺少来源元数据的片段被跳过() {
        recorder.record(1L, List.of(content(null, null, null, "没有出处的内容")));

        assertTrue(recorder.drain(1L).isEmpty(), "无docId/source的片段不应产出引用");
    }

    @Test
    public void 纯文本路径无页码时不影响其余字段() {
        recorder.record(1L, List.of(content("医院信息.md", "医院信息.md", null, "门诊时间")));

        SourceRef ref = recorder.drain(1L).get(0);
        assertNull(ref.page(), "拿不到页码时不应编造");
        assertEquals("医院信息.md", ref.location(), "无页码时只显示文档名");
    }

    @Test
    public void 单会话来源条数有上限() {
        for (int i = 0; i < 20; i++) {
            recorder.record(1L, List.of(content("doc" + i + ".md", "doc" + i + ".md", i, "内容" + i)));
        }

        List<SourceRef> sources = recorder.drain(1L);
        assertEquals(8, sources.size(), "超过上限的来源不应无限增长，否则前端卡片会刷屏");
    }
}
