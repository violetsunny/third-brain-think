package top.kdla.framework.llm.mentor.rag.dto;

import lombok.Data;

/**
 * 文档切片参数
 */
@Data
public class DocumentSplitParam {
    
    /**
     * 切片类型
     */
    private String splitType;
    
    /**
     * 最大分段长度
     */
    private Integer chunkSize;
    
    /**
     * 分段重叠长度
     */
    private Integer overlap;
    
    /**
     * 标题级数
     */
    private Integer titleLevel;
    
    /**
     * 分隔符
     */
    private String separator;
    
    /**
     * 正则表达式
     */
    private String regex;
    
    public DocumentSplitParam(String splitType, Integer chunkSize, Integer overlap, 
                              Integer titleLevel, String separator, String regex) {
        this.splitType = splitType;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.titleLevel = titleLevel;
        this.separator = separator;
        this.regex = regex;
    }
}
