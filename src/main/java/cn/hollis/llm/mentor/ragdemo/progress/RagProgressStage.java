package cn.hollis.llm.mentor.ragdemo.progress;

/**
 * RAG 管道各阶段枚举，用于结构化进度事件推送。
 * 顺序反映标准 RAG 管道执行顺序。
 */
public enum RagProgressStage {

    QUERY_TRANSFORM("Query 转换"),
    VECTOR_SEARCH("向量检索"),
    KEYWORD_SEARCH("关键词检索"),
    RRF_FUSION("RRF 融合"),
    RERANK("重排序"),
    GENERATING("生成中");

    private final String label;

    RagProgressStage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
