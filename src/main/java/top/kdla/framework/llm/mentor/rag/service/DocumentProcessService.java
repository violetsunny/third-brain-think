package top.kdla.framework.llm.mentor.rag.service;

import top.kdla.framework.llm.mentor.rag.dto.BatchResult;
import top.kdla.framework.llm.mentor.rag.dto.BatchUploadParam;
import top.kdla.framework.llm.mentor.rag.dto.DocumentSplitParam;
import top.kdla.framework.llm.mentor.rag.dto.DocumentUploadParam;
import top.kdla.framework.llm.mentor.rag.entity.KnowledgeDocument;

import java.util.List;

/**
 * 文档处理服务接口
 */
public interface DocumentProcessService {
    
    /**
     * 上传文档
     *
     * @param param 上传参数
     * @return 文档实体
     */
    KnowledgeDocument upload(DocumentUploadParam param);

    /**
     * 批量上传文档。单文件失败不影响其他文件。
     *
     * @param param 批量上传参数（含文件列表和共享元数据）
     * @return 批量操作结果（success docIds + failed fileName→reason）
     * @throws IllegalArgumentException 当文件数量超过 batch-max-size 时
     */
    BatchResult batchUpload(BatchUploadParam param);

    /**
     * 批量删除文档。单文档删除失败不影响其他文档。
     *
     * @param docIds 待删除的文档 ID 列表
     * @return 批量操作结果（success docIds + failed docId→reason）
     */
    BatchResult batchDelete(List<String> docIds);
    
    /**
     * 切片文档
     *
     * @param documentId 文档ID
     * @param param      切片参数
     * @return 切片数量
     */
    Integer split(Long documentId, DocumentSplitParam param);

    /**
     * 级联删除文档：向量存储 → ES → 分段 → 版本 → 文档记录
     *
     * @param docId 文档ID（knowledge_document.doc_id）
     */
    void delete(String docId);
}

