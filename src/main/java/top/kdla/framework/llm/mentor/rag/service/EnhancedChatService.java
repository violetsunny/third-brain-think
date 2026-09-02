package top.kdla.framework.llm.mentor.rag.service;

import top.kdla.framework.llm.mentor.rag.ai.IntentRecognitionResult;
import top.kdla.framework.llm.mentor.rag.ai.IntentRecognitionService;
import top.kdla.framework.llm.mentor.rag.ai.StreamThinkTagFilter;
import top.kdla.framework.llm.mentor.rag.memory.DatabaseChatMemoryStore;
import top.kdla.framework.llm.mentor.rag.retrieval.HyDEQueryTransformer;
import top.kdla.framework.llm.mentor.rag.retrieval.MultiSourceQueryRouter;
import top.kdla.framework.llm.mentor.rag.retrieval.QueryRouter;
import top.kdla.framework.llm.mentor.rag.transformer.QueryTransformerFactory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * 增强版流式聊天服务
 * 整合了意图识别、QueryRouter 路由、查询转换、混合检索、进度推送等功能
 *
 * <p>升级点（3.0.0）:
 * <ul>
 *   <li>注入 {@link QueryRouter}，在 RAG 路径中根据查询特征路由到最适合的检索策略：
 *       NONE → 纯 LLM 回答，KEYWORD → 关键词检索，其他（VECTOR/HYBRID）→ 完整混合检索</li>
 *   <li>每次提问前 evictCache，防止上一轮 RAG 引用污染意图识别</li>
 * </ul>
 *
 * <p>升级点（3.1.0）:
 * <ul>
 *   <li>支持 HyDE（Hypothetical Document Embeddings）查询变换，通过 {@code rag.hyde.enabled} 开关控制</li>
 * </ul>
 */
@Slf4j
@Service
public class EnhancedChatService {

    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final ContentRetriever ragContentRetriever;
    /** 关键词检索器：ES 激活时为 BM25，否则为 MySQL LIKE 降级实现。可为 null（两者均未注册时跳过） */
    private final ContentRetriever keywordContentRetriever;
    private final IntentRecognitionService intentRecognitionService;
    private final QueryTransformerFactory queryTransformerFactory;
    private final ContentAggregator contentAggregator;
    private final DatabaseChatMemoryStore databaseChatMemoryStore;
    private final QueryRouter queryRouter;
    private final HyDEQueryTransformer hyDEQueryTransformer;
    /**
     * 多数据源查询路由器（第二级路由）。仅当 {@code rag.retrieval.enable-multi-source-routing=true}
     * 时注册为 Bean，默认为 null（不影响现有检索流程）。
     */
    @Autowired(required = false)
    private MultiSourceQueryRouter multiSourceQueryRouter;

    @Value("${rag.hyde.enabled:false}")
    private boolean hydeEnabled;

    // 缓存普通聊天服务（无状态，可复用）
    private final SimpleChatService cachedSimpleChatService;

    public EnhancedChatService(StreamingChatModel streamingChatModel,
                               ChatMemoryProvider chatMemoryProvider,
                               @Qualifier("ragContentRetriever") ContentRetriever ragContentRetriever,
                               @Autowired(required = false) @Qualifier("keywordContentRetriever") ContentRetriever keywordContentRetriever,
                               IntentRecognitionService intentRecognitionService,
                               QueryTransformerFactory queryTransformerFactory,
                               ContentAggregator contentAggregator,
                               DatabaseChatMemoryStore databaseChatMemoryStore,
                               QueryRouter queryRouter,
                               HyDEQueryTransformer hyDEQueryTransformer) {
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.ragContentRetriever = ragContentRetriever;
        this.keywordContentRetriever = keywordContentRetriever;
        this.intentRecognitionService = intentRecognitionService;
        this.queryTransformerFactory = queryTransformerFactory;
        this.contentAggregator = contentAggregator;
        this.databaseChatMemoryStore = databaseChatMemoryStore;
        this.queryRouter = queryRouter;
        this.hyDEQueryTransformer = hyDEQueryTransformer;

        // 预创建普通聊天服务（不需要每次重建）
        this.cachedSimpleChatService = AiServices.builder(SimpleChatService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();

        log.info("EnhancedChatService initialized with QueryRouter + DatabaseChatMemoryStore, keywordRetriever={}",
                keywordContentRetriever != null ? keywordContentRetriever.getClass().getSimpleName() : "none");
    }

    /**
     * 流式聊天（带意图识别、QueryRouter 路由和进度推送）
     *
     * @param userId           用户ID（作为会话记忆ID）
     * @param conversationId   会话ID
     * @param message          用户消息
     * @param progressCallback 进度回调函数
     * @return 流式响应
     */
    public Flux<String> streamChat(String userId, String conversationId, String message,
                                   Consumer<String> progressCallback) {
        log.info("Enhanced chat request - user: {}, conversation: {}, message length: {}",
                userId, conversationId, message.length());

        // 0. evictCache: 清除 Redis 中的 chat memory，防止上一轮 RAG 引用污染意图识别
        databaseChatMemoryStore.evictCache(conversationId);

        // 1. 意图识别
        IntentRecognitionResult intent;
        try {
            if (progressCallback != null) {
                progressCallback.accept("[PROGRESS]:正在分析意图...");
            }

            intent = intentRecognitionService.recognize(message);
            log.info("意图识别结果: related={}, type={}", intent.isRelated(), intent.getIntentType());

        } catch (Exception e) {
            log.error("意图识别失败，默认使用RAG", e);
            intent = new IntentRecognitionResult();
            intent.setRelated(true);
            intent.setIntentType("RAG");
        }

        // 2. 根据意图选择处理策略
        if (!intent.isRelated()) {
            // 普通聊天（不使用RAG）
            log.info("使用普通聊天服务");
            if (progressCallback != null) {
                progressCallback.accept("[PROGRESS]:正在生成回复...");
            }
            return StreamThinkTagFilter.filter(commonChat(userId, message));
        } else {
            // RAG 问答：先通过 QueryRouter 决定检索策略
            QueryRouter.RouteType routeType = queryRouter.route(message);
            log.info("QueryRouter 路由结果: {}", routeType);
            return StreamThinkTagFilter.filter(
                    ragChat(userId, conversationId, message, progressCallback, routeType));
        }
    }

    /** 普通聊天（不带RAG） */
    private Flux<String> commonChat(String userId, String message) {
        return cachedSimpleChatService.streamChat(userId, message);
    }

    /**
     * RAG 聊天（带检索增强）
     *
     * @param routeType QueryRouter 决定的路由类型：
     *                  NONE → 不检索，直接回答；
     *                  KEYWORD → 仅关键词检索；
     *                  VECTOR / HYBRID → 完整混合检索
     */
    private Flux<String> ragChat(String userId, String conversationId, String message,
                                 Consumer<String> progressCallback,
                                 QueryRouter.RouteType routeType) {

        // NONE: 查询路由判定无需检索（纯计算/闲聊等）
        if (routeType == QueryRouter.RouteType.NONE) {
            log.info("QueryRouter=NONE, 跳过检索直接生成回答");
            if (progressCallback != null) {
                progressCallback.accept("[PROGRESS]:正在生成回答...");
            }
            return commonChat(userId, message);
        }

        // 1. 查询转换（带进度）
        if (progressCallback != null) {
            progressCallback.accept("[PROGRESS]:正在转换查询...");
        }
        QueryTransformer queryTransformer = queryTransformerFactory.create(conversationId, progressCallback);

        // 1b. 若 HyDE 启用，用 HyDEQueryTransformer 覆盖 queryTransformer
        if (hydeEnabled) {
            log.info("HyDE 已启用，将使用 HyDEQueryTransformer 进行查询变换");
            queryTransformer = hyDEQueryTransformer;
        }

        // 2. 根据路由类型选择检索器
        ContentRetriever selectedRetriever;
        if (routeType == QueryRouter.RouteType.KEYWORD) {
            log.info("QueryRouter=KEYWORD, 使用关键词检索器");
            selectedRetriever = keywordContentRetriever;
        } else {
            // VECTOR 或 HYBRID → 完整混合检索
            // 若多数据源路由已启用，记录第二级路由结果（预留扩展：Graph/Relational 检索未来在此分支）
            if (multiSourceQueryRouter != null) {
                MultiSourceQueryRouter.SourceType sourceType = multiSourceQueryRouter.route(message);
                log.info("MultiSourceQueryRouter 路由结果: {}（当前版本统一走向量检索，GRAPH/RELATIONAL 预留扩展）",
                        sourceType);
            }
            log.info("QueryRouter={}, 使用混合检索器", routeType);
            selectedRetriever = ragContentRetriever;
        }

        // 3. 创建检索增强器
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(selectedRetriever))
                .queryTransformer(queryTransformer)
                .contentAggregator(contentAggregator)
                .build();

        // 4. 创建 RAG AI 服务
        var aiService = AiServices.builder(RagChatService.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                .build();

        return aiService.streamChat(userId, message);
    }

    /** 简单聊天服务接口（非RAG） */
    private interface SimpleChatService {
        Flux<String> streamChat(@MemoryId String userId,
                                @UserMessage String message);
    }

    /** RAG 聊天服务接口 */
    private interface RagChatService {
        @SystemMessage("""
                你是一个专业的RAG问答助手。请根据提供的上下文信息，详细、准确地回答用户的问题。
                
                ## 任务要求：
                1. 请基于以下提供的参考文档内容，回答用户的问题。
                2. 如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
                3. 尽量贴合用户的问题需求，提供有价值的回答。
                
                ## 格式要求：
                1. 使用清晰的段落结构
                2. 重要信息可以加粗或列表展示
                
                注意：如果参考文档内容为空，请直接回答"没有找到相关信息"。
                """)
        Flux<String> streamChat(@MemoryId String userId,
                                @UserMessage String message);
    }
}
