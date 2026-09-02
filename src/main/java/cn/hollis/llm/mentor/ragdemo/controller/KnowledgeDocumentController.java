package cn.hollis.llm.mentor.ragdemo.controller;

import cn.hollis.llm.mentor.ragdemo.dto.BatchResult;
import cn.hollis.llm.mentor.ragdemo.dto.BatchUploadParam;
import cn.hollis.llm.mentor.ragdemo.dto.DocumentSplitParam;
import cn.hollis.llm.mentor.ragdemo.dto.DocumentUploadParam;
import cn.hollis.llm.mentor.ragdemo.constant.DocumentStatus;
import cn.hollis.llm.mentor.ragdemo.entity.KnowledgeDocument;
import cn.hollis.llm.mentor.ragdemo.entity.KnowledgeSegment;
import cn.hollis.llm.mentor.ragdemo.embedding.VectorStoreService;
import cn.hollis.llm.mentor.ragdemo.mapper.KnowledgeSegmentMapper;
import cn.hollis.llm.mentor.ragdemo.service.DocumentProcessService;
import cn.hollis.llm.mentor.ragdemo.versioning.KnowledgeDocumentVersion;
import cn.hollis.llm.mentor.ragdemo.versioning.KnowledgeDocumentVersionService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档控制器
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class KnowledgeDocumentController {
    
    private final DocumentProcessService documentProcessService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeSegmentMapper knowledgeSegmentMapper;
    private final cn.hollis.llm.mentor.ragdemo.mapper.KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    
    /**
     * 文件上传接口
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile file,
            @NotBlank(message = "uploadUser 不能为空") @RequestParam("uploadUser") String uploadUser,
            @NotBlank(message = "title 不能为空") @RequestParam("title") String title,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam("description") String description,
            @RequestParam("knowledgeBaseType") String knowledgeBaseType,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy) throws IOException {
        
        log.info("Uploading document: {}, user: {}, type: {}", file.getOriginalFilename(), uploadUser, knowledgeBaseType);
        
        DocumentUploadParam param = new DocumentUploadParam(
                file, uploadUser, title, accessibleBy, description, knowledgeBaseType, tableName
        );
        
        KnowledgeDocument document = documentProcessService.upload(param);
        
        Map<String, Object> response = new HashMap<>();
        response.put("docId", document.getDocId());
        response.put("docTitle", document.getTitle());
        response.put("uploadUser", document.getUploadUser());
        response.put("status", document.getStatus());
        
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // Batch Operations (under /api/knowledge/...)
    // ─────────────────────────────────────────────────────────────

    /**
     * 批量上传文档
     * POST /api/knowledge/batch-upload
     */
    @PostMapping("/knowledge/batch-upload")
    public ResponseEntity<BatchResult> batchUpload(
            @RequestParam("files") List<MultipartFile> files,
            @NotBlank(message = "knowledgeBaseType 不能为空") @RequestParam("knowledgeBaseType") String knowledgeBaseType,
            @NotBlank(message = "uploadUser 不能为空") @RequestParam("uploadUser") String uploadUser,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy,
            @RequestParam(value = "description", required = false) String description) {

        log.info("Batch upload: {} files, user={}, type={}", files.size(), uploadUser, knowledgeBaseType);
        BatchUploadParam param = new BatchUploadParam(files, knowledgeBaseType, uploadUser, accessibleBy, description);
        try {
            BatchResult result = documentProcessService.batchUpload(param);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    /**
     * 批量删除文档
     * DELETE /api/knowledge/batch
     * 请求体: {"docIds": ["id1", "id2", ...]}
     */
    @DeleteMapping("/knowledge/batch")
    public BatchResult batchDelete(@RequestBody Map<String, List<String>> body) {
        List<String> docIds = body.get("docIds");
        log.info("Batch delete: docIds={}", docIds);
        return documentProcessService.batchDelete(docIds);
    }

    /**
     * 查询多个文档状态
     * GET /api/knowledge/status?docIds=id1,id2
     */
    @GetMapping("/knowledge/status")
    public List<Map<String, Object>> batchStatus(
            @NotEmpty(message = "docIds 不能为空") @RequestParam List<String> docIds) {

        log.info("Batch status query: docIds={}", docIds);

        // Fetch all documents in one query
        List<KnowledgeDocument> docs = knowledgeDocumentMapper.selectByDocIds(docIds);
        Map<String, KnowledgeDocument> docMap = new java.util.HashMap<>();
        for (KnowledgeDocument doc : docs) {
            docMap.put(doc.getDocId(), doc);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String docId : docIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", docId);
            KnowledgeDocument doc = docMap.get(docId);
            if (doc == null) {
                item.put("status", "NOT_FOUND");
                item.put("segmentCount", 0);
            } else {
                item.put("status", doc.getStatus());
                item.put("segmentCount", doc.getSegmentCount() != null ? doc.getSegmentCount() : 0);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 分页预览文档分段
     * GET /api/knowledge/{docId}/segments?page=0&size=10
     */
    @GetMapping("/knowledge/{docId}/segments")
    public ResponseEntity<Map<String, Object>> listSegments(
            @PathVariable String docId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("List segments for docId={}, page={}, size={}", docId, page, size);

        // Verify document exists — check by counting segments
        LambdaQueryWrapper<KnowledgeSegment> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(KnowledgeSegment::getDocId, docId);
        long total = knowledgeSegmentMapper.selectCount(countWrapper);

        if (total == 0) {
            // Could be no segments OR document doesn't exist — treat as 404
            return ResponseEntity.notFound().build();
        }

        Page<KnowledgeSegment> pageReq = new Page<>(page + 1, size); // MyBatis-Plus is 1-indexed
        LambdaQueryWrapper<KnowledgeSegment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeSegment::getDocId, docId)
               .orderByAsc(KnowledgeSegment::getSegmentIndex);
        IPage<KnowledgeSegment> segPage = knowledgeSegmentMapper.selectPage(pageReq, wrapper);

        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeSegment seg : segPage.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentIndex", seg.getSegmentIndex());
            String content = seg.getContent();
            item.put("content", content != null && content.length() > 200
                    ? content.substring(0, 200) : content);
            item.put("splitType", seg.getSplitType());
            item.put("status", seg.getStatus());
            items.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("docId", docId);
        response.put("page", page);
        response.put("size", size);
        response.put("total", segPage.getTotal());
        response.put("segments", items);

        return ResponseEntity.ok(response);
    }
    
    /**
     * 对文档进行切分
     */
    @PostMapping("/split/{documentId}")
    public Integer splitDocument(
            @PathVariable Long documentId,
            @RequestParam("splitType") String splitType,
            @RequestParam("chunkSize") Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap,
            @RequestParam(value = "regex", required = false) String regex,
            @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
            @RequestParam(value = "separator", required = false) String separator) {
        
        log.info("Splitting document: {}, type: {}, chunkSize: {}", documentId, splitType, chunkSize);
        
        DocumentSplitParam param = new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex);
        
        return documentProcessService.split(documentId, param);
    }

    // ─────────────────────────────────────────────────────────────
    // Task 7.7: Version management endpoints
    // ─────────────────────────────────────────────────────────────

    /**
     * 列出文档所有版本
     * GET /api/document/{docId}/versions
     */
    @GetMapping("/{docId}/versions")
    public List<KnowledgeDocumentVersion> listVersions(@PathVariable String docId) {
        log.info("Listing versions for docId: {}", docId);
        return versionService.listVersions(docId);
    }

    /**
     * 激活指定版本
     * POST /api/document/versions/{versionId}/activate
     */
    @PostMapping("/versions/{versionId}/activate")
    public Map<String, String> activateVersion(@PathVariable String versionId) {
        log.info("Activating version: {}", versionId);
        versionService.activateVersion(versionId);
        Map<String, String> response = new HashMap<>();
        response.put("versionId", versionId);
        response.put("status", "ACTIVE");
        return response;
    }

    /**
     * 停用指定版本
     * POST /api/document/versions/{versionId}/deactivate
     */
    @PostMapping("/versions/{versionId}/deactivate")
    public Map<String, String> deactivateVersion(@PathVariable String versionId) {
        log.info("Deactivating version: {}", versionId);
        versionService.deactivateVersion(versionId);
        Map<String, String> response = new HashMap<>();
        response.put("versionId", versionId);
        response.put("status", "INACTIVE");
        return response;
    }

    /**
     * 级联删除文档（向量 + ES + 分段 + 版本 + 文档记录）
     * DELETE /api/document/{docId}
     */
    @DeleteMapping("/{docId}")
    public Map<String, String> deleteDocument(@PathVariable String docId) {
        log.info("Cascade deleting document: {}", docId);
        documentProcessService.delete(docId);
        Map<String, String> response = new HashMap<>();
        response.put("docId", docId);
        response.put("status", "DELETED");
        return response;
    }

    /**
     * 重试向量化（适用于状态为 EMBED_FAILED 的文档）
     * POST /api/knowledge/{docId}/retry-embed
     */
    @PostMapping("/knowledge/{docId}/retry-embed")
    public ResponseEntity<Map<String, String>> retryEmbed(@PathVariable String docId) {
        log.info("Retry embed for docId={}", docId);

        // 查询文档
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDocument> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getDocId, docId);
        KnowledgeDocument doc = knowledgeDocumentMapper.selectOne(wrapper);

        if (doc == null) {
            return ResponseEntity.notFound().build();
        }

        if (!DocumentStatus.EMBED_FAILED.name().equals(doc.getStatus())) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "文档状态不是 EMBED_FAILED，当前状态: " + doc.getStatus());
            return ResponseEntity.badRequest().body(err);
        }

        // 查询切片
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeSegment> segWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        segWrapper.eq(KnowledgeSegment::getDocId, docId);
        List<KnowledgeSegment> segments = knowledgeSegmentMapper.selectList(segWrapper);

        if (segments.isEmpty()) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "文档无切片记录，请先执行切片");
            return ResponseEntity.badRequest().body(err);
        }

        // 重置状态为 SPLITTED，重新触发异步嵌入
        doc.setStatus(DocumentStatus.SPLITTED.name());
        knowledgeDocumentMapper.updateById(doc);

        List<dev.langchain4j.data.segment.TextSegment> textSegments = segments.stream()
                .map(s -> dev.langchain4j.data.segment.TextSegment.from(s.getContent()))
                .collect(java.util.stream.Collectors.toList());

        vectorStoreService.addSegmentsAsync(docId, textSegments, embeddingModel);

        Map<String, String> response = new HashMap<>();
        response.put("docId", docId);
        response.put("status", "EMBEDDING");
        return ResponseEntity.ok(response);
    }
}

