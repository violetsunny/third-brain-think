package cn.hollis.llm.mentor.ragdemo.controller;

import cn.hollis.llm.mentor.ragdemo.service.RagCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 演示 Controller
 * 提供文档上传、处理和查询 API
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagDemoController {

    private final RagCoreService ragCoreService;

    private static final String UPLOAD_DIR = "./uploads";

    /**
     * 上传并处理文档
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        log.info("Uploading document: {}", file.getOriginalFilename());
        
        try {
            // 创建上传目录
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            
            // 保存文件
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR, fileName);
            Files.write(filePath, file.getBytes());
            
            log.info("File saved to: {}", filePath.toAbsolutePath());
            
            // 处理文档
            var result = ragCoreService.processDocument(filePath.toString());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("fileName", fileName);
            response.put("filePath", filePath.toString());
            response.put("segmentCount", result.segments().size());
            response.put("message", "文档处理成功");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to upload document", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "文档处理失败: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * RAG 查询
     */
    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody QueryRequest request) {
        log.info("Query: {}", request.getQuestion());
        
        try {
            String answer = ragCoreService.query(request.getQuestion());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("answer", answer);
            response.put("question", request.getQuestion());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Query failed", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 简单 RAG 示例 - 上传并即时问答
     */
    @PostMapping("/simple-query")
    public ResponseEntity<Map<String, Object>> simpleQuery(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question) {
        
        log.info("Simple RAG Query: file={}, question={}", 
                 file.getOriginalFilename(), question);
        
        try {
            // 上传文件
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR, fileName);
            Files.write(filePath, file.getBytes());
            
            // 处理文档
            ragCoreService.processDocument(filePath.toString());
            
            // 执行查询
            String answer = ragCoreService.query(question);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("answer", answer);
            response.put("question", question);
            response.put("fileName", fileName);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Simple query failed", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * RAG 查询（带来源）
     */
    @PostMapping("/query-with-sources")
    public ResponseEntity<Map<String, Object>> queryWithSources(@RequestBody QueryRequest request) {
        log.info("Query with sources: {}", request.getQuestion());
        
        try {
            RagCoreService.QueryResult result = ragCoreService.queryWithSources(request.getQuestion());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("answer", result.answer());
            response.put("sources", result.sources());
            response.put("question", request.getQuestion());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Query failed", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "RAG Demo");
        return ResponseEntity.ok(response);
    }

    /**
     * 查询请求 DTO
     */
    public static class QueryRequest {
        private String question;
        private String sessionId;  // 可选，用于会话记忆

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
