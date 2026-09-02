package top.kdla.framework.llm.mentor.rag.versioning;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档版本实体
 * 对应 knowledge_document_version 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersion {

    @TableId(type = IdType.INPUT)
    @TableField("version_id")
    private String versionId;

    @TableField("doc_id")
    private String docId;

    @TableField("version")
    private Integer version;

    /** SHA-256 hash of the file content */
    @TableField("content_hash")
    private String contentHash;

    /** ACTIVE or INACTIVE */
    @TableField("status")
    private String status;

    @TableField("changelog")
    private String changelog;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
