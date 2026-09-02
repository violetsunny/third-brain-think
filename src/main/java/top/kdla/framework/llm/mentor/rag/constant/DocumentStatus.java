package top.kdla.framework.llm.mentor.rag.constant;

/**
 * 文档状态枚举
 */
public enum DocumentStatus {
    /**
     * 已上传
     */
    UPLOADED,
    
    /**
     * 已解析
     */
    PARSED,
    
    /**
     * 已切片
     */
    SPLITTED,

    /**
     * 向量化中
     */
    EMBEDDING,

    /**
     * 已向量化
     */
    EMBEDDED,

    /**
     * 向量化失败
     */
    EMBED_FAILED,

    /**
     * 失败
     */
    FAILED
}
