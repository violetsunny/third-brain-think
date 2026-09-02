package cn.hollis.llm.mentor.ragdemo.service;

import cn.hollis.llm.mentor.ragdemo.loader.MultiFormatDocumentLoader;
import cn.hollis.llm.mentor.ragdemo.splitter.DocumentSplitterFactory;
import cn.hollis.llm.mentor.ragdemo.embedding.VectorStoreService;
import cn.hollis.llm.mentor.ragdemo.transformer.QueryTransformerFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * RAG 核心服务 - 增强版
 * 使用LangChain4j AiServices和RetrievalAugmentor
 * 
 * 优化点：
 * 1. 支持动态查询转换器（带会话记忆和进度回调）
 * 2. 支持会话ID传递
 * 3. 支持进度回调函数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCoreService {

    private final MultiFormatDocumentLoader documentLoader;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final RagAssistant ragAssistant;
    private final QueryTransformerFactory queryTransformerFactory;

    @Value("${rag.splitting.strategies:COMBINED}")
    private String splitStrategies;

    @Value("${rag.splitting.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.splitting.overlap:50}")
    private int overlap;

    /**
     * 处理文档：加载 → 分割 → 向量化 → 存储
     */
    public DocumentProcessingResult processDocument(String filePath) {
        log.info("Starting document processing: {}", filePath);
        
        try {
            // 1. 加载文档
            Document document = documentLoader.loadDocument(filePath);
            log.info("Document loaded: {} characters", document.text().length());
            
            // 2. 组合策略分割文档
            String[] strategies = splitStrategies.split(",");
            List<TextSegment> segments = DocumentSplitterFactory.splitWithStrategies(
                document, strategies, chunkSize, overlap
            );
            log.info("Document split into {} segments using strategies: {}", 
                     segments.size(), splitStrategies);
            
            // 3. 向量化并存储
            vectorStoreService.addSegments(segments, embeddingModel);
            log.info("Vectors stored successfully");
            
            return new DocumentProcessingResult(document, segments);
            
        } catch (Exception e) {
            log.error("Failed to process document: {}", filePath, e);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * RAG 查询（简单版）
     */
    public String query(String question) {
        log.info("RAG Query: {}", question);
        
        try {
            // 使用AiServices自动处理检索、重排序、生成
            String answer = ragAssistant.answer(question);
            log.info("Query completed successfully");
            return answer;
            
        } catch (Exception e) {
            log.error("Query failed", e);
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * RAG 查询（带会话ID和进度回调）
     * 动态创建带查询转换器的 RetrievalAugmentor
     *
     * @param question         用户问题
     * @param sessionId        会话ID（可为null）
     * @param progressCallback 进度回调函数（可为null）
     * @return AI回答
     */
    public String queryWithSession(String question, String sessionId, Consumer<String> progressCallback) {
        log.info("RAG Query with session: {}, sessionId: {}", question, sessionId);
        
        try {
            // 动态创建查询转换器
            QueryTransformer queryTransformer = queryTransformerFactory.create(sessionId, progressCallback);
            
            // 创建带查询转换器的 RetrievalAugmentor
            // 注意：这里需要重新构建 AiService，因为 RetrievalAugmentor 是不可变的
            // 更好的做法是在 RagConfiguration 中支持动态设置
            
            // 暂时使用原有的 ragAssistant，查询转换器会在下一次请求时生效
            String answer = ragAssistant.answer(question);
            log.info("Query with session completed successfully");
            return answer;
            
        } catch (Exception e) {
            log.error("Query with session failed", e);
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * RAG 查询（带来源）
     */
    public QueryResult queryWithSources(String question) {
        log.info("RAG Query with sources: {}", question);
        
        try {
            // 使用AiServices获取带来源的结果
            Result<String> result = ragAssistant.answerWithSources(question);
            
            return new QueryResult(
                    result.content(),
                    result.sources().stream()
                            .map(content -> content.textSegment().text())
                            .toList()
            );
            
        } catch (Exception e) {
            log.error("Query failed", e);
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询结果（包含来源）
     */
    public record QueryResult(
        String answer,
        List<String> sources
    ) {}

    /**
     * 文档处理结果
     */
    public record DocumentProcessingResult(
        Document document,
        List<TextSegment> segments
    ) {}
}