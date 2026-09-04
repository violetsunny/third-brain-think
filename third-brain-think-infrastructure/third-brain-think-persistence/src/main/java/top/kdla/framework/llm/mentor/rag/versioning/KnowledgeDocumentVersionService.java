package top.kdla.framework.llm.mentor.rag.versioning;

import java.util.List;

/**
 * 文档版本管理服务接口
 */
public interface KnowledgeDocumentVersionService {

    /**
     * 为文档创建新版本（SHA-256 内容去重）
     *
     * @param docId     文档ID
     * @param fileBytes 文件内容字节数组（用于计算 SHA-256 hash）
     * @param changelog 变更说明（可为 null）
     * @return 新建的版本记录（若内容与已有版本重复则返回已有版本）
     */
    KnowledgeDocumentVersion createVersion(String docId, byte[] fileBytes, String changelog);

    /**
     * 列出文档的所有版本，按 version 降序
     */
    List<KnowledgeDocumentVersion> listVersions(String docId);

    /**
     * 激活指定版本（将 knowledge_document.current_version_id 更新为该版本）
     */
    void activateVersion(String versionId);

    /**
     * 停用指定版本（若为当前激活版本，回退至 version 最大的其他 ACTIVE 版本，或置 NULL）
     */
    void deactivateVersion(String versionId);
}
