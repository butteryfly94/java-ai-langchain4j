package com.mayaping.ai.langchain4j;

import com.mayaping.ai.langchain4j.rag.ingestion.StructuredDocumentLoader;
import com.mayaping.ai.langchain4j.rag.ingestion.StructuredDocumentLoader.ContentElement;
import com.mayaping.ai.langchain4j.rag.ingestion.StructuredDocumentLoader.ElementType;
import com.mayaping.ai.langchain4j.rag.ingestion.TableAwareSplitter;
import com.mayaping.ai.langchain4j.rag.ingestion.TableAwareSplitter.Block;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格感知切分的单元测试。
 *
 * 刻意不标 @SpringBootTest：这些断言只关心切分逻辑，不需要 ES / DashScope / MinerU，
 * 应该能在任何机器上秒级跑完。索引管线的正确性不该依赖外部服务是否启动。
 *
 * 覆盖的核心不变量：
 * - 表格不被拦腰切断（这是改造前 DocumentByParagraphSplitter 的主要失效模式）
 * - 每个行级子块都带表头（否则"心内科 1234"这种碎片脱离表头后语义为零）
 * - 超长表格拆片时，**每一片都重复表头**
 * - 表格解析失败时降级并告警，而不是静默丢弃
 */
public class TableAwareSplitterTest {

    private static final String DOC_ID = "科室信息.md";
    private static final String SOURCE = "科室信息.md";

    private static final String TABLE_HTML = """
            <table>
              <tr><th>科室</th><th>门诊时间</th><th>咨询电话</th></tr>
              <tr><td>心内科</td><td>周一至周五</td><td>1234</td></tr>
              <tr><td>神经内科</td><td>周一至周四</td><td>5678</td></tr>
            </table>
            """;

    private final TableAwareSplitter splitter = new TableAwareSplitter(500);

    @Test
    public void 短表格整体作为一个父块不被切断() {
        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.HEADING, "门诊科室一览", 1, List.of()),
                new ContentElement(ElementType.TABLE, TABLE_HTML, 1, List.of("各科室出诊安排")));

        List<String> warnings = new ArrayList<>();
        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, warnings);

        List<Block> tableBlocks = blocks.stream()
                .filter(b -> "table".equals(b.metadata().get("contentType")))
                .toList();

        assertEquals(1, tableBlocks.size(), "短表格应恰好产出一个父块，而不是被切开");

        String parent = tableBlocks.get(0).parentText();
        assertTrue(parent.contains("心内科"), "父块应包含全部数据行，实际：" + parent);
        assertTrue(parent.contains("神经内科"), "父块应包含全部数据行，实际：" + parent);
        assertTrue(parent.contains("门诊时间"), "父块应包含表头");
        assertTrue(warnings.isEmpty(), "正常表格不应产生告警：" + warnings);
    }

    @Test
    public void 每个行级子块都带表头且自然语言化() {
        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.TABLE, TABLE_HTML, 1, List.of()));

        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, new ArrayList<>());
        List<String> children = blocks.get(0).childTexts();

        assertEquals(2, children.size(), "两行数据应产出两个行级子块");

        // 这是整个改造的关键：子块必须是"表头：值"的自然语言，而不是 | 心内科 | 周一至周五 |
        // 否则向量检索拿到的是一条无归属的碎片
        String firstChild = children.get(0);
        assertTrue(firstChild.contains("科室：心内科"), "子块应带表头，实际：" + firstChild);
        assertTrue(firstChild.contains("门诊时间：周一至周五"), "子块应带表头，实际：" + firstChild);
        assertTrue(firstChild.contains("咨询电话：1234"), "子块应带表头，实际：" + firstChild);
        assertFalse(firstChild.contains("|"), "子块应是自然语言，不应保留竖线表格格式");
    }

    @Test
    public void 表格块带章节与表格说明做上下文() {
        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.HEADING, "神经内科简介", 2, List.of()),
                new ContentElement(ElementType.TABLE, TABLE_HTML, 2, List.of("各科室出诊安排")));

        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, new ArrayList<>());
        Block tableBlock = blocks.stream()
                .filter(b -> "table".equals(b.metadata().get("contentType")))
                .findFirst()
                .orElseThrow();

        String parent = tableBlock.parentText();
        assertTrue(parent.contains("所属章节：神经内科简介"), "父块应带章节上下文，实际：" + parent);
        assertTrue(parent.contains("表格说明：各科室出诊安排"), "父块应带表格说明，实际：" + parent);
        assertEquals(2, tableBlock.metadata().get("page"), "页码应落入元数据供引用溯源使用");
    }

    @Test
    public void 超长表格拆分后每一片都重复表头() {
        StringBuilder html = new StringBuilder("<table><tr><th>科室</th><th>门诊时间</th></tr>");
        for (int i = 1; i <= 80; i++) {
            html.append("<tr><td>科室").append(i)
                    .append("</td><td>周一至周五上午八点到下午五点</td></tr>");
        }
        html.append("</table>");

        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.TABLE, html.toString(), 1, List.of()));

        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, new ArrayList<>());
        List<Block> tableBlocks = blocks.stream()
                .filter(b -> "table".equals(b.metadata().get("contentType")))
                .toList();

        assertTrue(tableBlocks.size() > 1,
                "80行长表应被拆成多片，实际 " + tableBlocks.size() + " 片");

        // 关键不变量：拆开的每一片都必须自带表头。少了这一条，
        // 第二片之后的数据行就变成无归属的碎片，与被切断的坏数据没有区别
        for (int i = 0; i < tableBlocks.size(); i++) {
            String parent = tableBlocks.get(i).parentText();
            assertTrue(parent.contains("门诊时间"),
                    "第" + (i + 1) + "片缺少表头，实际：" + parent);
            assertTrue(parent.contains("（续 " + (i + 1) + "/" + tableBlocks.size() + "）"),
                    "第" + (i + 1) + "片应标注续页序号");
        }
    }

    @Test
    public void 无表头的表格不把首行数据当表头丢掉() {
        String noHeaderHtml = """
                <table>
                  <tr><td>心内科</td><td>1234</td></tr>
                  <tr><td>神经内科</td><td>5678</td></tr>
                </table>
                """;

        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.TABLE, noHeaderHtml, 1, List.of()));

        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, new ArrayList<>());

        assertEquals(2, blocks.get(0).childTexts().size(),
                "没有<th>时首行也是数据，两行都应有自己的子块");
        assertTrue(blocks.get(0).childTexts().get(0).contains("心内科"),
                "首行数据不应被当成表头吞掉");
    }

    @Test
    public void 表格解析失败时降级为正文并告警() {
        // 没有 <tr> 结构，解析不出任何行列
        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.TABLE, "这里本该是表格但结构已损坏", 3, List.of()));

        List<String> warnings = new ArrayList<>();
        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, warnings);

        assertFalse(warnings.isEmpty(), "解析失败必须告警，不能静默丢弃");
        assertTrue(warnings.get(0).contains("无法解析"), "告警应说明原因：" + warnings);

        // 内容退化成正文块保留下来，而不是凭空消失
        assertFalse(blocks.isEmpty(), "表格解析失败时内容应降级为正文入库");
        assertTrue(blocks.stream().anyMatch(b -> b.parentText().contains("本该是表格")),
                "降级后原文本仍应可见");
    }

    @Test
    public void 超过子块上限时截断并显式告警() {
        StringBuilder html = new StringBuilder("<table><tr><th>科室</th></tr>");
        for (int i = 1; i <= 10; i++) {
            html.append("<tr><td>科室").append(i).append("</td></tr>");
        }
        html.append("</table>");

        TableAwareSplitter smallCap = new TableAwareSplitter(3);
        List<String> warnings = new ArrayList<>();
        List<Block> blocks = smallCap.split(
                List.of(new ContentElement(ElementType.TABLE, html.toString(), 1, List.of())),
                DOC_ID, SOURCE, warnings);

        int totalChildren = blocks.stream().mapToInt(b -> b.childTexts().size()).sum();
        assertEquals(3, totalChildren, "子块总数应被上限截断");

        assertTrue(warnings.stream().anyMatch(w -> w.contains("超过单表子块上限")),
                "截断必须告警，不能静默，实际：" + warnings);

        // 被截断的行仍完整保留在父块里，父块召回时依然可达
        String allParents = blocks.stream().map(Block::parentText).reduce("", String::concat);
        assertTrue(allParents.contains("科室10"), "超出上限的行仍应保留在父块中");
    }

    @Test
    public void 正文与表格混排时保持顺序且各自元数据类型正确() {
        List<ContentElement> elements = List.of(
                new ContentElement(ElementType.HEADING, "科室介绍", 1, List.of()),
                new ContentElement(ElementType.TEXT, "我院设有多个临床科室，覆盖常见病与多发病的诊治。", 1, List.of()),
                new ContentElement(ElementType.TABLE, TABLE_HTML, 2, List.of()),
                new ContentElement(ElementType.TEXT, "以上排班如有调整，以门诊大厅公告为准。", 2, List.of()));

        List<Block> blocks = splitter.split(elements, DOC_ID, SOURCE, new ArrayList<>());

        List<String> types = blocks.stream()
                .map(b -> (String) b.metadata().get("contentType"))
                .toList();
        assertEquals(List.of("prose", "table", "prose"), types,
                "正文-表格-正文的顺序应被保留，实际：" + types);
    }

    @Test
    public void 结构化文档ID由content_list文件名推导() {
        assertEquals("就诊须知.md",
                StructuredDocumentLoader.docIdOf(Path.of("/tmp/就诊须知_content_list.json")));
        assertEquals("体检中心.md",
                StructuredDocumentLoader.docIdOf(Path.of("/tmp/体检中心_content_list.json")));
    }

    @Test
    public void 识别MinerU产物的文件名特征() {
        assertTrue(StructuredDocumentLoader.looksLikeStructuredContent(
                Path.of("/tmp/就诊须知_content_list.json")));
        assertFalse(StructuredDocumentLoader.looksLikeStructuredContent(
                Path.of("/tmp/package.json")));
        assertFalse(StructuredDocumentLoader.looksLikeStructuredContent(
                Path.of("/tmp/就诊须知.md")));
    }

    @Test
    public void 解析MinerU的content_list样例文件() throws Exception {
        Path fixture = Path.of("src/test/resources/mineru-sample/就诊须知_content_list.json")
                .toAbsolutePath();
        assertTrue(fixture.toFile().exists(), "测试样例缺失：" + fixture);

        List<ContentElement> elements = StructuredDocumentLoader.load(fixture);

        assertEquals(6, elements.size(), "样例含6个内容块");

        // page_idx 是 0-based，落库的页码必须是 1-based，否则引用溯源会整体偏一页
        assertEquals(1, elements.get(0).page(), "第0页应转换为第1页");
        assertEquals(2, elements.get(3).page(), "page_idx=1 应转换为第2页");

        assertEquals(ElementType.HEADING, elements.get(1).type());

        ContentElement table = elements.get(3);
        assertEquals(ElementType.TABLE, table.type());
        assertTrue(table.text().contains("<table>"), "表格正文应取 table_body 的HTML");
        assertTrue(table.captions().contains("各科室门诊排班表"), "应解析出表格题注");
        assertTrue(table.captions().contains("节假日安排另行通知"), "应解析出表格脚注");

        // 图片暂无多模态能力：保留元素以便统计，但文本为空
        assertEquals(ElementType.IMAGE, elements.get(4).type());
        assertTrue(elements.get(4).isBlank(), "图片块不携带文本");

        assertEquals(ElementType.EQUATION, elements.get(5).type());
        assertTrue(elements.get(5).text().contains("BMI"), "公式应保留原始文本");
    }

    @Test
    public void 样例文件端到端切出表格块与正文块() throws Exception {
        Path fixture = Path.of("src/test/resources/mineru-sample/就诊须知_content_list.json")
                .toAbsolutePath();
        List<ContentElement> elements = StructuredDocumentLoader.load(fixture);

        List<String> warnings = new ArrayList<>();
        List<Block> blocks = splitter.split(elements, "就诊须知.md", "就诊须知.md", warnings);

        List<Block> tableBlocks = blocks.stream()
                .filter(b -> "table".equals(b.metadata().get("contentType")))
                .toList();
        assertEquals(1, tableBlocks.size(), "样例中的表格应产出1个表格块");

        // "心内科的电话是多少" 这类问题要能命中：子块必须自带表头与具体值
        String child = tableBlocks.get(0).childTexts().get(0);
        assertTrue(child.contains("科室：心内科"), "实际：" + child);
        assertTrue(child.contains("咨询电话：025-12345678"), "实际：" + child);

        assertTrue(blocks.stream().anyMatch(b -> "prose".equals(b.metadata().get("contentType"))),
                "样例中的正文应产出正文块");
        assertTrue(warnings.isEmpty(), "正常样例不应产生告警：" + warnings);
    }
}
