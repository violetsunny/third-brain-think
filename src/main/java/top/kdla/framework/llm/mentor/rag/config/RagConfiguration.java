package top.kdla.framework.llm.mentor.rag.config;

import top.kdla.framework.llm.mentor.rag.ai.CommonChatService;
import top.kdla.framework.llm.mentor.rag.ai.IntentRecognitionService;
import top.kdla.framework.llm.mentor.rag.ai.RagStreamingService;
import top.kdla.framework.llm.mentor.rag.embedding.VectorStoreService;
import top.kdla.framework.llm.mentor.rag.memory.DatabaseChatMemoryStore;
import top.kdla.framework.llm.mentor.rag.progress.ProgressAwareContentAggregator;
import top.kdla.framework.llm.mentor.rag.progress.ProgressAwareContentRetriever;
import top.kdla.framework.llm.mentor.rag.progress.RagProgressStage;
import top.kdla.framework.llm.mentor.rag.rerank.ReRankingContentAggregator;
import top.kdla.framework.llm.mentor.rag.rerank.RRFContentAggregator;
import top.kdla.framework.llm.mentor.rag.retrieval.BrotherAwareRetriever;
import top.kdla.framework.llm.mentor.rag.retrieval.HybridContentRetriever;
import top.kdla.framework.llm.mentor.rag.retrieval.HybridRetrievalService;
import top.kdla.framework.llm.mentor.rag.retrieval.VectorContentRetriever;
import top.kdla.framework.llm.mentor.rag.service.RagAssistant;
import top.kdla.framework.llm.mentor.rag.transformer.ContextAwareQueryTransformer;
import top.kdla.framework.llm.mentor.rag.transformer.QueryExpansionTransformer;
import top.kdla.framework.llm.mentor.rag.transformer.QueryTransformerFactory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RAG配置类
 * 组装RetrievalAugmentor和AiServices
 *
 * 增强点:
 * - 使用 ProgressAwareContentRetriever 包装混合检索器
 * - 使用 ProgressAwareContentAggregator 包装重排序聚合器
 * - ChatMemoryProvider 使用 DatabaseChatMemoryStore（Redis + MySQL 双写）
 */
@Slf4j
@Configuration
public class RagConfiguration {

    @Value("${rag.retrieval.vector-top-k:10}")
    private int vectorTopK;

    @Value("${rag.retrieval.similarity-threshold:0.6}")
    private double similarityThreshold;

    @Value("${rag.retrieval.enable-rerank:true}")
    private boolean enableRerank;

    @Value("${rag.rerank.api-key:sk-9ce1c5bd677c46c7b2ebeae50b3ec19b}")
    private String rerankApiKey;

    @Value("${rag.rerank.provider:dashscope}")
    private String rerankProvider;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    @Value("${rag.retrieval.fine-top-k:5}")
    private int fineTopK;

    @Value("${rag.retrieval.rrf-top-k:20}")
    private int rrfTopK;

    @Value("${rag.chat.memory.window-size:10}")
    private int memoryWindowSize;

    // ─────────────────────────────────────────────────────────────
    // Chat Memory — DatabaseChatMemoryStore-backed
    // ─────────────────────────────────────────────────────────────

    /**
     * ChatMemoryProvider backed by DatabaseChatMemoryStore (Redis + MySQL).
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(DatabaseChatMemoryStore store) {
        log.info("创建持久化 ChatMemoryProvider (window={})", memoryWindowSize);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(memoryWindowSize)
                .chatMemoryStore(store)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Retrievers
    // ─────────────────────────────────────────────────────────────

    /**
     * 向量检索器（不直接暴露为 Bean，用于 Progress 包装）
     */
    @Bean("rawVectorContentRetriever")
    public ContentRetriever rawVectorContentRetriever(VectorStoreService vectorStoreService,
                                                      EmbeddingModel embeddingModel) {
        return VectorContentRetriever.builder()
                .vectorStoreService(vectorStoreService)
                .embeddingModel(embeddingModel)
                .maxResults(vectorTopK)
                .minScore(similarityThreshold)
                .build();
    }

    /**
     * 创建查询路由器（目前只使用向量检索，后续可扩展）
     */
    @Bean
    public QueryRouter queryRouter(ContentRetriever vectorContentRetriever) {
        log.info("创建查询路由器");
        return new DefaultQueryRouter(vectorContentRetriever);
    }

    /**
     * 创建查询转换器工厂
     */
    @Bean
    public QueryTransformerFactory queryTransformerFactory(ChatModel chatModel,
                                                           StringRedisTemplate redisTemplate) {
        log.info("创建查询转换器工厂");
        return new QueryTransformerFactory(chatModel, redisTemplate);
    }

    // ─────────────────────────────────────────────────────────────
    // Content Aggregator with Progress decoration
    // ─────────────────────────────────────────────────────────────

    /**
     * 创建内容聚合器（重排序）+ ProgressAware 包装
     */
    @Bean
    public ContentAggregator contentAggregator() {
        ContentAggregator base;
        if (enableRerank) {
            log.info("创建重排序聚合器: provider={}, model={}, maxResults={}",
                    rerankProvider, rerankModel, fineTopK);
            base = ReRankingContentAggregator.builder()
                    .apiKey(rerankApiKey)
                    .provider(rerankProvider)
                    .model(rerankModel)
                    .maxResults(fineTopK)
                    .minScore(0.0)
                    .build();
        } else {
            log.info("重排序已禁用，使用 RRF 聚合器");
            base = RRFContentAggregator.builder()
                    .maxResults(rrfTopK)
                    .build();
        }
        // Wrap with progress decorator
        return ProgressAwareContentAggregator.of(base);
    }

    /**
     * 创建RetrievalAugmentor（RAG核心组件）
     * 当 ContextAwareQueryTransformer / QueryExpansionTransformer Bean 存在时自动注入
     * 顺序: ContextAware（上下文改写）→ QueryExpansion（查询扩展）
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(
            QueryRouter queryRouter,
            ContentAggregator contentAggregator,
            @Autowired(required = false) ContextAwareQueryTransformer contextAwareQueryTransformer,
            @Autowired(required = false) QueryExpansionTransformer queryExpansionTransformer) {

        var builder = DefaultRetrievalAugmentor.builder()
                .queryRouter(queryRouter)
                .contentAggregator(contentAggregator);

        // 按优先级组合 Transformer 链: ContextAware -> QueryExpansion
        if (contextAwareQueryTransformer != null && queryExpansionTransformer != null) {
            log.info("RetrievalAugmentor: ContextAwareQueryTransformer + QueryExpansionTransformer 均已注入");
            // 先上下文改写，再扩展查询
            QueryTransformer chain = query -> {
                var contextRewritten = contextAwareQueryTransformer.transform(query);
                return contextRewritten.stream()
                        .flatMap(q -> queryExpansionTransformer.transform(q).stream())
                        .toList();
            };
            builder.queryTransformer(chain);
        } else if (contextAwareQueryTransformer != null) {
            log.info("RetrievalAugmentor: ContextAwareQueryTransformer 已注入");
            builder.queryTransformer(contextAwareQueryTransformer);
        } else if (queryExpansionTransformer != null) {
            log.info("RetrievalAugmentor: QueryExpansionTransformer 已注入");
            builder.queryTransformer(queryExpansionTransformer);
        } else {
            log.info("创建RetrievalAugmentor（不含QueryTransformer，将在运行时动态设置）");
        }

        return builder.build();
    }

    /**
     * 创建RAG ContentRetriever（混合检索，带 Progress 包装）
     */
    @Bean
    public ContentRetriever ragContentRetriever(HybridRetrievalService hybridRetrievalService) {
        log.info("创建RAG ContentRetriever（混合检索 + Progress 装饰器）");
        ContentRetriever hybrid = new HybridContentRetriever(hybridRetrievalService);
        return ProgressAwareContentRetriever.of(hybrid, RagProgressStage.VECTOR_SEARCH);
    }

    /**
     * 兄弟感知 ContentRetriever。
     *
     * <p>{@link BrotherAwareRetriever} 的实际检索逻辑由 RAG pipeline 内部直接调用，
     * 此 Bean 仅用于满足 Spring 的依赖注入（使 BrotherAwareRetriever 得以初始化），
     * 不会被注册到检索路由链中。不包装 ProgressAware 装饰器，以避免发出永远不代表
     * 真实工作的进度事件。
     */
    @Bean
    public ContentRetriever brotherContentRetriever(BrotherAwareRetriever brotherAwareRetriever) {
        log.info("创建兄弟感知 ContentRetriever（仅用于依赖注入初始化，检索由 pipeline 内部触发）");
        // Returns empty list — brother retrieval is triggered inside the RAG pipeline directly.
        // Do NOT wrap with ProgressAwareContentRetriever: this delegate is a no-op and would
        // emit a misleading "正在检索关联片段..." progress event for zero actual work.
        return query -> java.util.Collections.emptyList();
    }

    /**
     * 创建意图识别服务
     */
    @Bean
    public IntentRecognitionService intentRecognitionService(ChatModel chatModel) {
        log.info("创建意图识别服务");
        return AiServices.builder(IntentRecognitionService.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * 创建普通聊天服务（非RAG）
     */
    @Bean
    public CommonChatService commonChatService(StreamingChatModel openAiStreamingChatModel,
                                                ChatMemoryProvider chatMemoryProvider) {
        log.info("创建普通聊天服务");
        return AiServices.builder(CommonChatService.class)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    /**
     * 创建RAG流式服务
     */
    @Bean
    public RagStreamingService ragStreamingService(StreamingChatModel openAiStreamingChatModel,
                                                    ChatMemoryProvider chatMemoryProvider,
                                                    ContentRetriever ragContentRetriever) {
        log.info("创建RAG流式服务");
        return AiServices.builder(RagStreamingService.class)
                .streamingChatModel(openAiStreamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(ragContentRetriever)
                .build();
    }

    /**
     * 创建RagAssistant（AI服务 - 非流式）
     */
    @Bean
    public RagAssistant ragAssistant(ChatModel chatModel,
                                      RetrievalAugmentor retrievalAugmentor,
                                      ChatMemoryProvider chatMemoryProvider) {
        log.info("创建RagAssistant");
        return AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .retrievalAugmentor(retrievalAugmentor)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }
}
