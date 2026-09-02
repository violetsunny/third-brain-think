package top.kdla.framework.llm.mentor.rag.constant;

/**
 * 片段状态枚举
 */
public enum SegmentStatus {
    /**
     * 已创建
     */
    CREATED,
    
    /**
     * 已向量化
     */
    EMBEDDED,
    
    /**
     * 失败
     */
    FAILED
}
