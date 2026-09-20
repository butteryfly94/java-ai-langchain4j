package com.mayaping.ai.langchain4j.rag.citation;

/**
 * 一条引用来源：答案所依据的知识片段出自哪份文档、哪一页。
 *
 * 为什么需要它：ES里的元数据改造前只有 file_name，回答错了无从倒查。
 * 医疗场景要能核对原文出处（合规要求），而且页码必须**在索引时就写进元数据**——
 * 事后补是补不回来的，索引重建一次就丢了。
 *
 * @param docId   文档标识（如 神经内科.md）
 * @param source  来源文件名，通常与docId一致；仅docId时取docId
 * @param page    1-based页码；来自纯文本路径时为null（不编造）
 * @param snippet 片段摘要，供用户快速判断是否相关
 */
public record SourceRef(String docId, String source, Integer page, String snippet) {

    /** 摘要保留的最大字符数，太长会把前端卡片撑爆 */
    private static final int MAX_SNIPPET_CHARS = 120;

    public static SourceRef of(String docId, String source, Integer page, String text) {
        return new SourceRef(docId, source, page, snippetOf(text));
    }

    private static String snippetOf(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SNIPPET_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    /**
     * 去重键：同一份文档的同一页只保留一条。
     * 一次检索常会命中同一页的多个子块，不去重的话前端会列出重复来源。
     */
    public String dedupKey() {
        return (source == null ? docId : source) + "#" + page;
    }

    /** 前端展示用的位置描述 */
    public String location() {
        String name = source == null || source.isBlank() ? docId : source;
        if (name == null) {
            name = "未知来源";
        }
        return page == null ? name : name + " 第" + page + "页";
    }
}
