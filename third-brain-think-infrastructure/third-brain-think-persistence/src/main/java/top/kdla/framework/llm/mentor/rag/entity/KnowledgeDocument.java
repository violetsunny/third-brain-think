package top.kdla.framework.llm.mentor.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体类
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 文档唯一标识
     */
    @TableField("doc_id")
    private String docId;
    
    /**
     * 文档标题
     */
    @TableField("title")
    private String title;
    
    /**
     * 文档描述
     */
    @TableField("description")
    private String description;
    
    /**
     * 文件名称
     */
    @TableField("file_name")
    private String fileName;
    
    /**
     * 文件存储路径
     */
    @TableField("file_path")
    private String filePath;
    
    /**
     * 文件类型(pdf/doc/docx/md/txt等)
     */
    @TableField("file_type")
    private String fileType;
    
    /**
     * 文件大小(字节)
     */
    @TableField("file_size")
    private Long fileSize;
    
    /**
     * 知识库类型: DOCUMENT_SEARCH-文档搜索, DATA_QUERY-数据查询
     */
    @TableField("knowledge_base_type")
    private String knowledgeBaseType;
    
    /**
     * 数据表名称(仅DATA_QUERY模式使用)
     */
    @TableField("table_name")
    private String tableName;
    
    /**
     * 上传用户
     */
    @TableField("upload_user")
    private String uploadUser;
    
    /**
     * 可见范围，多个团队用逗号分隔
     */
    @TableField("accessible_by")
    private String accessibleBy;
    
    /**
     * 文档状态: UPLOADED-已上传, PARSED-已解析, SPLITTED-已切片, EMBEDDED-已向量化, FAILED-失败
     */
    @TableField("status")
    private String status;
    
    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;
    
    /**
     * 切片数量
     */
    @TableField("segment_count")
    private Integer segmentCount;
    
    /**
     * 当前激活的版本ID（关联 knowledge_document_version.version_id）
     */
    @TableField("current_version_id")
    private String currentVersionId;

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
