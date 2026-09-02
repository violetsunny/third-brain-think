package cn.hollis.llm.mentor.ragdemo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档上传参数
 */
@Data
public class DocumentUploadParam {
    
    /**
     * 上传的文件
     */
    private MultipartFile file;
    
    /**
     * 上传用户
     */
    private String uploadUser;
    
    /**
     * 文档标题
     */
    private String title;
    
    /**
     * 可见范围
     */
    private String accessibleBy;
    
    /**
     * 文档描述
     */
    private String description;
    
    /**
     * 知识库类型
     */
    private String knowledgeBaseType;
    
    /**
     * 数据表名称（可选）
     */
    private String tableName;
    
    public DocumentUploadParam(MultipartFile file, String uploadUser, String title, 
                               String accessibleBy, String description, 
                               String knowledgeBaseType, String tableName) {
        this.file = file;
        this.uploadUser = uploadUser;
        this.title = title;
        this.accessibleBy = accessibleBy;
        this.description = description;
        this.knowledgeBaseType = knowledgeBaseType;
        this.tableName = tableName;
    }
}
