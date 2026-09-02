package cn.hollis.llm.mentor.ragdemo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 批量上传文档参数（共享元数据，多文件）
 */
@Data
public class BatchUploadParam {

    /**
     * 待上传的文件列表
     */
    private List<MultipartFile> files;

    /**
     * 知识库类型（所有文件共享）
     */
    private String knowledgeBaseType;

    /**
     * 上传用户（所有文件共享）
     */
    private String uploadUser;

    /**
     * 可见范围（所有文件共享，可选）
     */
    private String accessibleBy;

    /**
     * 文档描述（所有文件共享，可选）
     */
    private String description;

    public BatchUploadParam(List<MultipartFile> files, String knowledgeBaseType,
                            String uploadUser, String accessibleBy, String description) {
        this.files = files;
        this.knowledgeBaseType = knowledgeBaseType;
        this.uploadUser = uploadUser;
        this.accessibleBy = accessibleBy;
        this.description = description;
    }
}
