package com.mayaping.ai.langchain4j.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MinerU 产物 {@code <name>_content_list.json} 的解析器。
 *
 * 为什么管线要读这个JSON而不是读MinerU同时产出的 .md：
 * Markdown里表格长这样 {@code | 科室 | 门诊时间 |}，在 DocumentByParagraphSplitter 眼里
 * 和普通文本毫无区别——内部没有空行，整张表算"一个段落"，超过maxChars就被按行切断，
 * 且切出来的碎片没有表头。也就是说：**只转Markdown只解决了"解析"这一层，
 * "切分"这一层一点没动，表格照样被切坏**。
 *
 * content_list.json 带 type 字段（title/text/table/equation），切分器据此才知道
 * "这块是表格，不许切"。md 仍然保留，但它的价值是让人快速核对MinerU解析得对不对，
 * 不进管线。
 *
 * 字段兼容性说明：MinerU 各版本输出的字段名并不完全一致（如表格正文可能是
 * table_body，个别版本是 text）。这里对每种元素都做了多字段兜底，
 * 拿不到内容时返回空白元素由上层校验闸门告警，而不是静默丢弃。
 */
public final class StructuredDocumentLoader {

    private static final Logger log = LoggerFactory.getLogger(StructuredDocumentLoader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MinerU 输出的块类型 */
    public enum ElementType {
        HEADING, TEXT, LIST, TABLE, EQUATION, IMAGE, UNKNOWN
    }

    /**
     * 一个内容块。
     *
     * @param page 1-based页码（MinerU的page_idx是0-based，这里已+1）；无页码信息时为null
     */
    public record ContentElement(ElementType type, String text, Integer page, List<String> captions) {

        public boolean isTable() {
            return type == ElementType.TABLE;
        }

        public boolean isHeading() {
            return type == ElementType.HEADING;
        }

        public boolean isBlank() {
            return text == null || text.isBlank();
        }
    }

    private StructuredDocumentLoader() {
    }

    /**
     * 判断某个json是否像MinerU的输出。用于区分"结构化产物"与"普通json"，
     * 避免把无关的json当文档索引进去。
     */
    public static boolean looksLikeStructuredContent(Path jsonPath) {
        String name = jsonPath.getFileName().toString();
        return name.endsWith(".json") && name.contains("content_list");
    }

    /**
     * 从 {@code <name>_content_list.json} 的路径推出文档ID。
     * 约定：{@code 就诊须知_content_list.json} → {@code 就诊须知.md}，
     * 这样同一份文档无论走结构化路径还是纯文本路径，docId一致，引用溯源才不会串。
     */
    public static String docIdOf(Path jsonPath) {
        String name = jsonPath.getFileName().toString();
        String base = name.substring(0, name.length() - ".json".length());
        if (base.endsWith("_content_list")) {
            base = base.substring(0, base.length() - "_content_list".length());
        }
        return base + ".md";
    }

    /**
     * 解析content_list.json。
     *
     * 兼容两种顶层结构：直接是数组，或 {@code {"content": [...]}} 包装。
     * 都拿不到时抛异常——解析失败必须显式失败，不能返回空列表让上层以为"这份文档没内容"。
     */
    public static List<ContentElement> load(Path jsonPath) throws IOException {
        JsonNode root = MAPPER.readTree(jsonPath.toFile());
        JsonNode array = root.isArray() ? root : findFirstArrayField(root);

        if (array == null) {
            throw new IOException("无法识别的content_list结构（既不是数组，也不含数组字段）：" + jsonPath);
        }

        List<ContentElement> elements = new ArrayList<>();
        for (JsonNode node : array) {
            ContentElement element = toElement(node);
            if (element != null) {
                elements.add(element);
            }
        }
        log.debug("解析 {} 得到 {} 个内容块", jsonPath.getFileName(), elements.size());
        return elements;
    }

    private static JsonNode findFirstArrayField(JsonNode root) {
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            JsonNode child = root.get(it.next());
            if (child != null && child.isArray()) {
                return child;
            }
        }
        return null;
    }

    private static ContentElement toElement(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }

        ElementType type = toType(node.path("type").asText(""));
        Integer page = node.hasNonNull("page_idx") ? node.path("page_idx").asInt(0) + 1 : null;

        String text = switch (type) {
            // 表格正文是HTML（MinerU输出的是结构化table），字段名跨版本有差异
            case TABLE -> firstNonBlank(
                    node.path("table_body").asText(null),
                    node.path("text").asText(null));
            case EQUATION -> firstNonBlank(
                    node.path("text").asText(null),
                    node.path("latex").asText(null));
            case IMAGE -> node.path("img_path").asText(null);
            default -> node.path("text").asText(null);
        };

        List<String> captions = new ArrayList<>();
        collectStrings(node, "table_caption", captions);
        collectStrings(node, "table_footnote", captions);
        collectStrings(node, "caption", captions);

        if (type == ElementType.IMAGE) {
            // 暂无多模态能力，图片块本身不入库；但保留元素让上层统计到"这里有图没被索引"
            return new ContentElement(type, "", page, captions);
        }
        if (text == null) {
            text = "";
        }
        return new ContentElement(type, text, page, captions);
    }

    private static ElementType toType(String rawType) {
        return switch (rawType) {
            case "title" -> ElementType.HEADING;
            case "table" -> ElementType.TABLE;
            case "list" -> ElementType.LIST;
            case "equation", "formula", "interline_equation" -> ElementType.EQUATION;
            case "image", "figure" -> ElementType.IMAGE;
            case "text" -> ElementType.TEXT;
            default -> ElementType.UNKNOWN;
        };
    }

    private static void collectStrings(JsonNode node, String field, List<String> sink) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> {
                String s = item.asText(null);
                if (s != null && !s.isBlank()) {
                    sink.add(s);
                }
            });
        } else {
            String s = value.asText(null);
            if (s != null && !s.isBlank()) {
                sink.add(s);
            }
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
