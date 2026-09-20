package com.mayaping.ai.langchain4j.rag.ingestion;

import com.mayaping.ai.langchain4j.rag.ingestion.StructuredDocumentLoader.ContentElement;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 表格感知切分器：以内容块的类型驱动切分，而不是按字符数盲切。
 *
 * 解决的三个问题：
 *
 * 1. **表格被拦腰切断**：表格内部没有空行，DocumentByParagraphSplitter 把它当"一个段落"，
 *    超过maxChars就退化成按行切，切出来的碎片既没表头也没上下文。
 *    这里把表格当作**原子单元**——整表作parent；确实超长时才按行拆分，
 *    但**每个分片都重复表头**并标注"（续 i/n）"。
 *
 * 2. **表格碎片语义为零**：一行 {@code | 心内科 | 周一至周五 | 1234 |} 脱离表头后
 *    完全无法理解。所以children是"行+表头"的**自然语言化**结果——
 *    {@code 科室：心内科；门诊时间：周一至周五；咨询电话：1234}。
 *    这对向量检索尤其重要：自然语句的embedding质量显著优于竖线分隔的表格行。
 *
 * 3. **表格块脱离上下文**：每个表格块都前置"所属章节 + 表格说明"，否则单独一个表格块
 *    被召回时，LLM既不知道这是哪份文档的，也不知道这张表在讲什么。
 *
 * 正文块仍然复用 DocumentByParagraphSplitter（父1000/子300），与纯md路径行为一致。
 */
@Component
public class TableAwareSplitter {

    private static final Logger log = LoggerFactory.getLogger(TableAwareSplitter.class);

    private static final int PROSE_PARENT_MAX_CHARS = 1000;
    private static final int PROSE_PARENT_OVERLAP_CHARS = 100;
    private static final int PROSE_CHILD_MAX_CHARS = 300;
    private static final int PROSE_CHILD_OVERLAP_CHARS = 30;

    /** 表格拆分时给上下文前缀+表头预留的字符预算 */
    private static final int TABLE_OVERHEAD_RESERVE_CHARS = 300;
    /** 表头+上下文后至少还给数据留这么多字符，避免预算被榨干导致一行一块 */
    private static final int TABLE_MIN_DATA_BUDGET_CHARS = 200;

    /**
     * 一个可入库的块：父块文本 + 它的子块文本们 + 共用的元数据。
     * 元数据对父子块都适用（子块额外带parentId，由索引环节补）。
     */
    public record Block(String parentText, List<String> childTexts, Map<String, Object> metadata) {
    }

    private final DocumentByParagraphSplitter proseParentSplitter =
            new DocumentByParagraphSplitter(PROSE_PARENT_MAX_CHARS, PROSE_PARENT_OVERLAP_CHARS);
    private final DocumentByParagraphSplitter proseChildSplitter =
            new DocumentByParagraphSplitter(PROSE_CHILD_MAX_CHARS, PROSE_CHILD_OVERLAP_CHARS);

    private final int maxTableRowsPerChild;

    public TableAwareSplitter(@Value("${rag.ingestion.max-table-rows-per-child:200}") int maxTableRowsPerChild) {
        this.maxTableRowsPerChild = maxTableRowsPerChild;
    }

    /** 表格还原后的结构：表头 + 数据行（每行是若干单元格） */
    private record TableData(List<String> header, List<List<String>> rows) {
        boolean isEmpty() {
            return header.isEmpty() && rows.isEmpty();
        }
    }

    /**
     * 一组数据行。整表放得下时只有一个分组（整表作一个父块）；
     * 放不下才按字符预算拆成多组，每组在父块里重复表头。
     */
    private record RowGroup(List<List<String>> rows) {
    }

    /**
     * 把一个文档的内容块序列切成 (父块, 子块[]) 列表。
     *
     * @param warnings 校验告警收集器。解析异常在这里登记而不是静默丢弃——
     *                 "PDF解析失败是静默的"正是这条管线最大的坑，
     *                 没有显式告警，你只能等到检索效果变差才倒查
     */
    public List<Block> split(List<ContentElement> elements, String docId, String source, List<String> warnings) {
        List<Block> blocks = new ArrayList<>();
        List<ContentElement> proseBuffer = new ArrayList<>();
        String section = null;

        for (ContentElement element : elements) {
            if (element.type() == StructuredDocumentLoader.ElementType.IMAGE) {
                // 暂无多模态能力，图片不入库。这里不告警以免噪声淹没真问题
                continue;
            }

            if (element.isTable()) {
                flushProse(blocks, proseBuffer, section, docId, source);
                List<Block> tableBlocks = toTableBlocks(element, section, docId, source, warnings);
                if (tableBlocks.isEmpty()) {
                    // 表格HTML解析不出任何行列：退化成正文块并告警，绝不静默丢弃
                    warnings.add(String.format(
                            "[%s] 第%s页的表格无法解析出行列结构，已降级为普通文本入库",
                            docId, element.page() == null ? "?" : element.page()));
                    proseBuffer.add(element);
                } else {
                    blocks.addAll(tableBlocks);
                }
                continue;
            }

            if (element.isHeading() && !element.isBlank()) {
                // 记录最新章节标题，供其后出现的表格做上下文前缀
                section = element.text().trim();
            }

            if (!element.isBlank()) {
                proseBuffer.add(element);
            }
        }

        flushProse(blocks, proseBuffer, section, docId, source);
        return blocks;
    }

    // ==================== 正文切分 ====================

    /**
     * 把累积的正文块合并后按 父1000/子300 切分，与纯md路径行为一致。
     * 合并而非逐块切分，是为了让段落能正常跨块聚合，避免产出大量过短的块。
     */
    private void flushProse(List<Block> blocks, List<ContentElement> buffer, String section,
                            String docId, String source) {
        if (buffer.isEmpty()) {
            return;
        }

        // 页码必须在清空buffer之前取
        Integer page = firstPageOf(buffer);

        String text = buffer.stream()
                .map(ContentElement::text)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
        buffer.clear();

        if (text.isEmpty()) {
            return;
        }

        Map<String, Object> metadata = baseMetadata(docId, source, page, "prose", section);
        Document document = Document.from(text, toMetadata(metadata));

        for (TextSegment parent : proseParentSplitter.split(document)) {
            List<String> children = proseChildSplitter
                    .split(Document.from(parent.text(), parent.metadata()))
                    .stream()
                    .map(TextSegment::text)
                    .toList();
            blocks.add(new Block(parent.text(), children, metadata));
        }
    }

    private Integer firstPageOf(List<ContentElement> elements) {
        return elements.stream()
                .map(ContentElement::page)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    // ==================== 表格切分 ====================

    private List<Block> toTableBlocks(ContentElement table, String section, String docId, String source,
                                      List<String> warnings) {
        TableData data = parseHtmlTable(table.text());
        if (data.isEmpty()) {
            return List.of();
        }

        String context = buildTableContext(section, table.captions());
        List<RowGroup> groups = groupRows(data.rows(), data.header(), context);

        // 行级child让"心内科咨询电话"能精确命中那一行，但超大表会产出上千个child与等量
        // embedding调用。超出上限的行不再单独索引——它们仍完整保留在父块里，
        // 父块被召回时依然可达，只是无法被单独命中。这里显式告警，不做静默截断。
        int totalRows = data.rows().size();
        if (totalRows > maxTableRowsPerChild) {
            warnings.add(String.format(
                    "[%s] 第%s页表格共%d行，超过单表子块上限%d，超出部分不单独索引（仍保留在父块中）",
                    docId, table.page() == null ? "?" : table.page(), totalRows, maxTableRowsPerChild));
        }

        List<Block> blocks = new ArrayList<>();
        int emittedRows = 0;

        for (int i = 0; i < groups.size(); i++) {
            List<List<String>> groupRows = groups.get(i).rows();
            String continuation = groups.size() > 1
                    ? String.format("（续 %d/%d）", i + 1, groups.size())
                    : "";

            String parentText = context + continuation + "\n" + toMarkdownTable(data.header(), groupRows);

            // 子块 = 单行 + 表头，自然语言化。这样"心内科咨询电话是多少"能精确命中那一行，
            // 而答案上下文由父块（整表或整组分片）提供
            List<String> children = new ArrayList<>();
            for (List<String> row : groupRows) {
                if (emittedRows >= maxTableRowsPerChild) {
                    break;
                }
                String child = toNaturalLanguageRow(data.header(), row);
                if (!child.isBlank()) {
                    children.add(child);
                    emittedRows++;
                }
            }

            blocks.add(new Block(parentText, children,
                    baseMetadata(docId, source, table.page(), "table", section)));
        }
        return blocks;
    }

    /**
     * 解析MinerU输出的表格HTML。
     *
     * 对没有 {@code <th>} 的表，首行同样是数据——此时生成占位列名(列1/列2...)，
     * 而不是把一条真实数据行误当表头丢掉。
     */
    private TableData parseHtmlTable(String html) {
        if (html == null || html.isBlank()) {
            return new TableData(List.of(), List.of());
        }

        org.jsoup.nodes.Document document = Jsoup.parse(html);
        Elements rows = document.select("tr");
        if (rows.isEmpty()) {
            return new TableData(List.of(), List.of());
        }

        List<List<String>> parsed = new ArrayList<>();
        for (Element row : rows) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th, td")) {
                cells.add(cell.text().replaceAll("\\s+", " ").trim());
            }
            if (!cells.isEmpty()) {
                parsed.add(cells);
            }
        }
        if (parsed.isEmpty()) {
            return new TableData(List.of(), List.of());
        }

        boolean hasHeaderCell = !document.select("th").isEmpty();
        if (hasHeaderCell) {
            return new TableData(parsed.get(0), List.copyOf(parsed.subList(1, parsed.size())));
        }

        List<String> genericHeader = new ArrayList<>();
        for (int i = 0; i < parsed.get(0).size(); i++) {
            genericHeader.add("列" + (i + 1));
        }
        return new TableData(genericHeader, List.copyOf(parsed));
    }

    /**
     * 按字符预算把行分组。单组放得下时只有一组（整表作一个parent），
     * 放不下才拆，且每组都会在父块里重复表头。
     */
    private List<RowGroup> groupRows(List<List<String>> rows, List<String> header, String context) {
        if (rows.isEmpty()) {
            return List.of();
        }

        int budget = PROSE_PARENT_MAX_CHARS
                - context.length()
                - estimateHeaderChars(header)
                - TABLE_OVERHEAD_RESERVE_CHARS;
        budget = Math.max(budget, TABLE_MIN_DATA_BUDGET_CHARS);

        List<RowGroup> groups = new ArrayList<>();
        List<List<String>> current = new ArrayList<>();
        int currentChars = 0;

        for (List<String> row : rows) {
            int rowChars = row.stream().mapToInt(cell -> cell == null ? 0 : cell.length()).sum()
                    + row.size() * 3 + 2;
            if (!current.isEmpty() && currentChars + rowChars > budget) {
                groups.add(new RowGroup(current));
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(row);
            currentChars += rowChars;
        }
        if (!current.isEmpty()) {
            groups.add(new RowGroup(current));
        }
        return groups;
    }

    private int estimateHeaderChars(List<String> header) {
        return header.stream().mapToInt(cell -> cell == null ? 0 : cell.length()).sum()
                + header.size() * 3 + 4;
    }

    private String toMarkdownTable(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|");
        for (int i = 0; i < header.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString().trim();
    }

    /**
     * 把一行数据转成自然语言，例如
     * {@code 科室：心内科；门诊时间：周一至周五；咨询电话：1234}。
     * 用于子块——自然语句的embedding质量显著优于竖线分隔的表格行。
     */
    private String toNaturalLanguageRow(List<String> header, List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            String value = row.get(i);
            if (value == null || value.isBlank()) {
                continue;
            }
            String key = i < header.size() && !header.get(i).isBlank()
                    ? header.get(i)
                    : "列" + (i + 1);
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(key).append("：").append(value);
        }
        return sb.toString();
    }

    /**
     * 表格块的上下文前缀。没有它，单独一个表格块被召回时LLM既不知道来自哪份文档，
     * 也不知道这张表在讲什么。
     */
    private String buildTableContext(String section, List<String> captions) {
        StringBuilder sb = new StringBuilder("【表格】");
        if (section != null && !section.isBlank()) {
            sb.append("所属章节：").append(section).append("；");
        }
        if (captions != null && !captions.isEmpty()) {
            sb.append("表格说明：").append(String.join(" ", captions));
        }
        return sb.toString();
    }

    // ==================== 元数据 ====================

    private Map<String, Object> baseMetadata(String docId, String source, Integer page,
                                             String contentType, String section) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", docId);
        metadata.put("source", source);
        metadata.put("contentType", contentType);
        if (page != null) {
            // 页码是引用溯源的依据：答错了要能倒查原文出处，医疗场景的合规要求
            metadata.put("page", page);
        }
        if (section != null && !section.isBlank()) {
            metadata.put("section", section);
        }
        return metadata;
    }

    private Metadata toMetadata(Map<String, Object> raw) {
        Metadata metadata = new Metadata();
        metadata.putAll(raw);
        return metadata;
    }
}
