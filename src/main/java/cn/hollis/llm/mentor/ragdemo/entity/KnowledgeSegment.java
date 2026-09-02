package cn.hollis.llm.mentor.ragdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识片段实体类
 */
@Data
@TableName("knowledge_segment")
public class KnowledgeSegment {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 片段唯一标识
     */
    @TableField("segment_id")
    private String segmentId;
    
    /**
     * 所属文档ID
     */
    @TableField("doc_id")
    private String docId;
    
    /**
     * 片段内容
     */
    @TableField("content")
    private String content;
    
    /**
     * 片段索引序号
     */
    @TableField("segment_index")
    private Integer segmentIndex;
    
    /**
     * 切片方式: LENGTH/TITLE/REGEX/SMART/SEPARATOR
     */
    @TableField("split_type")
    private String splitType;
    
    /**
     * 元数据(JSON格式)，包含标题、页码等信息
     */
    @TableField("metadata")
    private String metadata;
    
    /**
     * 片段状态: CREATED-已创建, EMBEDDED-已向量化, FAILED-失败
     */
    @TableField("status")
    private String status;
    
    /**
     * 使用的Embedding模型
     */
    @TableField("embedding_model")
    private String embeddingModel;
    
    /**
     * 向量数据库中的ID
     */
    @TableField("vector_id")
    private String vectorId;
    
    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
