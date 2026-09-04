package top.kdla.framework.llm.mentor.rag.constant;

/**
 * 切片类型枚举
 */
public enum SplitType {
    /**
     * 按长度切分
     */
    LENGTH,
    
    /**
     * 按标题切分
     */
    TITLE,
    
    /**
     * 按正则切分
     */
    REGEX,
    
    /**
     * 智能切分
     */
    SMART,
    
    /**
     * 按分隔符切分
     */
    SEPARATOR,

    /**
     * Excel/CSV 表格切分（支持 .xlsx, .xls, .csv）
     */
    EXCEL,

    /**
     * Word 文档标题层级切分（支持 .doc, .docx），需开启 rag.word-splitter.enable=true
     */
    WORD
}
