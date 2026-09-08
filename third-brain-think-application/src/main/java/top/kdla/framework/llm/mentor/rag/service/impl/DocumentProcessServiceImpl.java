package top.kdla.framework.llm.mentor.rag.service.impl;

import top.kdla.framework.llm.mentor.rag.constant.DocumentStatus;
import top.kdla.framework.llm.mentor.rag.dto.BatchResult;
import top.kdla.framework.llm.mentor.rag.dto.BatchUploadParam;
import top.kdla.framework.llm.mentor.rag.dto.DocumentSplitParam;
import top.kdla.framework.llm.mentor.rag.dto.DocumentUploadParam;
import top.kdla.framework.llm.mentor.rag.embedding.VectorStoreService;
import top.kdla.framework.llm.mentor.rag.entity.KnowledgeDocument;
import top.kdla.framework.llm.mentor.rag.entity.KnowledgeSegment;
import top.kdla.framework.llm.mentor.rag.event.DocumentSplitCompletedEvent;
import top.kdla.framework.llm.mentor.rag.loader.DocumentCleaner;
import top.kdla.framework.llm.mentor.rag.loader.MultiFormatDocumentLoader;
import top.kdla.framework.llm.mentor.rag.mapper.KnowledgeDocumentMapper;
import top.kdla.framework.llm.mentor.rag.mapper.KnowledgeSegmentMapper;
import top.kdla.framework.llm.mentor.rag.retrieval.RetrievalCacheInvalidator;
import top.kdla.framework.llm.mentor.rag.versioning.KnowledgeDocumentVersion;
import top.kdla.framework.llm.mentor.rag.versioning.KnowledgeDocumentVersionMapper;
import top.kdla.framework.llm.mentor.rag.versioning.KnowledgeDocumentVersionService;
import top.kdla.framework.llm.mentor.rag.service.DocumentProcessService;
import top.kdla.framework.llm.mentor.rag.splitter.DocumentSplitterFactory;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档处理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessServiceImpl implements DocumentProcessService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final MultiFormatDocumentLoader documentLoader;
    private final KnowledgeDocumentVersionService versionService;
    private final DocumentCleaner documentCleaner;
    private final VectorStoreService vectorStoreService;
    private final RetrievalCacheInvalidator retrievalCacheInvalidator;
    private final EmbeddingModel embeddingModel;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    @Qualifier("elasticsearchClient")
    private ElasticsearchClient elasticsearchClient;

    @Value("${rag.document.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${rag.document.auto-split:true}")
    private boolean autoSplit;

    @Value("${rag.document.batch-max-size:20}")
    private int batchMaxSize;

    @Value("${rag.splitting.chunk-size:500}")
    private int defaultChunkSize;

    @Value("${rag.splitting.overlap:50}")
    private int defaultOverlap;

    @Value("${elasticsearch.index-name:rag_demo_index}")
    private String esIndexName;

    @Override
    public KnowledgeDocument upload(DocumentUploadParam param) {
        // Step 1: Write file BEFORE the transaction opens.
        // If the DB transaction fails, we clean up the orphan file in the catch block.
        String savedFilePath;
        try {
            savedFilePath = saveFile(param.getFile());
        } catch (Exception e) {
            log.error("Failed to save uploaded file", e);
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        try {
            return doUploadInTransaction(param, savedFilePath);
        } catch (Exception e) {
            // Transaction rolled back — delete the orphan file to stay consistent.
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(savedFilePath));
                log.info("Cleaned up orphan file after transaction failure: {}", savedFilePath);
            } catch (Exception deleteEx) {
                log.warn("Failed to clean up orphan file: {}", savedFilePath, deleteEx);
            }
            throw e;
        }
    }

    @Transactional
    public KnowledgeDocument doUploadInTransaction(DocumentUploadParam param, String filePath) {
        try {
            // 1. 创建文档记录
            KnowledgeDocument document = new KnowledgeDocument();
            document.setDocId(UUID.randomUUID().toString());
            document.setTitle(param.getTitle());
            document.setDescription(param.getDescription());
            document.setFileName(param.getFile().getOriginalFilename());
            document.setFilePath(filePath);
            document.setFileType(getFileExtension(param.getFile().getOriginalFilename()));
            document.setFileSize(param.getFile().getSize());
            document.setKnowledgeBaseType(param.getKnowledgeBaseType());
            document.setTableName(param.getTableName());
            document.setUploadUser(param.getUploadUser());
            document.setAccessibleBy(param.getAccessibleBy());
            document.setStatus(DocumentStatus.UPLOADED.name());
            document.setSegmentCount(0);
            document.setCreateTime(LocalDateTime.now());
            document.setUpdateTime(LocalDateTime.now());

            documentMapper.insert(document);
            log.info("Document uploaded: {}, docId: {}", param.getFile().getOriginalFilename(), document.getDocId());

            // 2. Create initial version record
            try {
                versionService.createVersion(document.getDocId(), param.getFile().getBytes(), "Initial upload");
            } catch (Exception e) {
                log.warn("Failed to create version record for docId={}, continuing", document.getDocId(), e);
            }

            // 3. 如果启用自动切片，立即执行切片
            if (autoSplit) {
                log.info("Auto-split enabled, starting split process...");
                DocumentSplitParam splitParam = new DocumentSplitParam(
                        "HYBRID", defaultChunkSize, defaultOverlap, null, null, null
                );
                split(document.getId(), splitParam);
            }
            return document;

        } catch (Exception e) {
            log.error("Failed to upload document", e);
            throw new RuntimeException("文档上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public Integer split(Long documentId, DocumentSplitParam param) {
        try {
            // 1. 获取文档
            KnowledgeDocument document = documentMapper.selectById(documentId);
            if (document == null) {
                throw new RuntimeException("文档不存在: " + documentId);
            }

            // 2. 加载文档内容，并执行文本清洗
            Document langChainDoc = documentLoader.loadDocument(document.getFilePath());
            String rawText = langChainDoc.text();
            String cleanedText = documentCleaner.cleanText(rawText);
            if (!cleanedText.equals(rawText)) {
                log.info("DocumentCleaner applied: {} → {} chars", rawText.length(), cleanedText.length());
                langChainDoc = Document.from(cleanedText, langChainDoc.metadata());
            }
            log.info("Document loaded for splitting: {} characters", langChainDoc.text().length());

            // 3. 执行切片
            List<TextSegment> segments = DocumentSplitterFactory.splitWithStrategies(
                    langChainDoc,
                    new String[]{param.getSplitType()},
                    param.getChunkSize(),
                    param.getOverlap() != null ? param.getOverlap() : defaultOverlap
            );

            log.info("Document split into {} segments", segments.size());

            // 4. 保存切片到数据库
            int index = 0;
            for (TextSegment segment : segments) {
                KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
                knowledgeSegment.setSegmentId(UUID.randomUUID().toString());
                knowledgeSegment.setDocId(document.getDocId());
                knowledgeSegment.setContent(segment.text());
                knowledgeSegment.setSegmentIndex(index++);
                knowledgeSegment.setSplitType(param.getSplitType());
                knowledgeSegment.setStatus("CREATED");
                knowledgeSegment.setCreateTime(LocalDateTime.now());
                knowledgeSegment.setUpdateTime(LocalDateTime.now());
                segmentMapper.insert(knowledgeSegment);
            }

            // 5. 更新文档状态为 SPLITTED，触发异步嵌入
            document.setStatus(DocumentStatus.SPLITTED.name());
            document.setSegmentCount(segments.size());
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);

            log.info("Document split completed: {} segments saved, triggering async embedding", segments.size());

            // 6. 发布切片完成事件，由 EmbeddingEventListener 在事务提交后异步嵌入
            // 使用 @TransactionalEventListener(phase = AFTER_COMMIT) 确保 DB 记录已可见，
            // 避免嵌入线程在事务提交前启动时读取到不一致的文档状态
            eventPublisher.publishEvent(new DocumentSplitCompletedEvent(
                    document.getDocId(), segments, embeddingModel));

            return segments.size();

        } catch (Exception e) {
            log.error("Failed to split document: {}", documentId, e);
            throw new RuntimeException("文档切片失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void delete(String docId) {
        log.info("Starting cascade delete for docId={}", docId);

        // Step 1: 删除 Milvus 向量（失败继续）
        try {
            vectorStoreService.deleteByDocumentId(docId);
        } catch (Exception e) {
            log.warn("Failed to delete Milvus vectors for docId={}, continuing", docId, e);
        }

        // Step 2: 删除 ES 中对应记录（失败继续）
        if (elasticsearchClient != null) {
            try {
                elasticsearchClient.deleteByQuery(d -> d
                        .index(esIndexName)
                        .query(q -> q.term(t -> t
                                .field("document_id")
                                .value(v -> v.stringValue(docId))
                        ))
                );
                log.info("Deleted ES documents for docId={}", docId);
            } catch (Exception e) {
                log.warn("Failed to delete ES documents for docId={}, continuing", docId, e);
            }
        }

        // Step 3: 删除 knowledge_segment 表记录
        LambdaQueryWrapper<KnowledgeSegment> segWrapper = new LambdaQueryWrapper<>();
        segWrapper.eq(KnowledgeSegment::getDocId, docId);
        int segDeleted = segmentMapper.delete(segWrapper);
        log.info("Deleted {} segments for docId={}", segDeleted, docId);

        // Step 4: 删除版本记录
        LambdaQueryWrapper<KnowledgeDocumentVersion> verWrapper = new LambdaQueryWrapper<>();
        verWrapper.eq(KnowledgeDocumentVersion::getDocId, docId);
        int verDeleted = versionMapper.delete(verWrapper);
        log.info("Deleted {} version records for docId={}", verDeleted, docId);

        // Step 5: 删除 knowledge_document 主记录
        LambdaQueryWrapper<KnowledgeDocument> docWrapper = new LambdaQueryWrapper<>();
        docWrapper.eq(KnowledgeDocument::getDocId, docId);
        int docDeleted = documentMapper.delete(docWrapper);
        log.info("Deleted {} document record for docId={}", docDeleted, docId);

        // Step 6: 清除检索缓存
        retrievalCacheInvalidator.invalidateByKnowledgeBaseType("default");
    }

    @Override
    public BatchResult batchUpload(BatchUploadParam param) {
        if (param.getFiles() == null || param.getFiles().isEmpty()) {
            throw new IllegalArgumentException("文件列表不能为空");
        }
        if (param.getFiles().size() > batchMaxSize) {
            throw new IllegalArgumentException(
                    "超出批量上传数量限制，最多支持 " + batchMaxSize + " 个文件，当前：" + param.getFiles().size());
        }

        BatchResult result = new BatchResult();
        for (org.springframework.web.multipart.MultipartFile file : param.getFiles()) {
            String fileName = file.getOriginalFilename();
            try {
                DocumentUploadParam singleParam = new DocumentUploadParam(
                        file,
                        param.getUploadUser(),
                        fileName,                  // 使用文件名作为标题
                        param.getAccessibleBy(),
                        param.getDescription(),
                        param.getKnowledgeBaseType(),
                        null
                );
                KnowledgeDocument doc = upload(singleParam);
                result.addSuccess(doc.getDocId());
                log.info("BatchUpload: succeeded for file={}", fileName);
            } catch (Exception e) {
                String key = fileName != null ? fileName : "unknown";
                result.addFailed(key, e.getMessage());
                log.warn("BatchUpload: failed for file={}: {}", fileName, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public BatchResult batchDelete(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            throw new IllegalArgumentException("docIds 不能为空");
        }

        BatchResult result = new BatchResult();
        for (String docId : docIds) {
            try {
                // Verify existence first
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDocument> wrapper =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
                wrapper.eq(KnowledgeDocument::getDocId, docId);
                KnowledgeDocument doc = documentMapper.selectOne(wrapper);
                if (doc == null) {
                    result.addFailed(docId, "文档不存在");
                    continue;
                }
                delete(docId);
                result.addSuccess(docId);
                log.info("BatchDelete: succeeded for docId={}", docId);
            } catch (Exception e) {
                result.addFailed(docId, e.getMessage());
                log.warn("BatchDelete: failed for docId={}: {}", docId, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 保存文件到本地
     */
    private String saveFile(org.springframework.web.multipart.MultipartFile file) throws Exception {
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path filePath = Paths.get(uploadDir, fileName);
        Files.write(filePath, file.getBytes());
        return filePath.toString();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
