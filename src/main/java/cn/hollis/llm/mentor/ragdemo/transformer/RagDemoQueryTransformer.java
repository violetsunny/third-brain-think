package cn.hollis.llm.mentor.ragdemo.transformer;

import com.google.common.base.Stopwatch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;

/**
 * RagDemo 增强版查询转换器
 * <p>
 * 基于 LLM 对用户查询进行智能改写优化，结合历史对话上下文，提升 RAG 检索效果。
 * 整合了 know-engine 的优秀特性：
 * <ul>
 *   <li><b>进度回调</b>：支持流式返回前端进度信息</li>
 *   <li><b>领域特定Prompt</b>：通用场景的智能改写策略</li>
 *   <li><b>会话记忆集成</b>：从 Redis 获取历史对话</li>
 *   <li><b>异步回写</b>：将改写结果保存到 Redis（可选）</li>
 * </ul>
 * <p>
 * <b>改写策略：</b>
 * <ol>
 *   <li><b>简洁改写</b>：删除无意义的语气词、修饰词，将疑问句转为陈述句</li>
 *   <li><b>抽象概念改写</b>：将具体问题转化为更基础、更抽象的查询表述</li>
 *   <li><b>错别字改写</b>：纠正错别字和拼音错误</li>
 *   <li><b>关键信息提取</b>：标准化提取用户提及的关键实体信息</li>
 *   <li><b>上下文补全</b>：结合历史对话识别相关细节和术语，将问题重组为独立完整的查询</li>
 * </ol>
 *
 * @see QueryTransformer
 */
@Slf4j
public class RagDemoQueryTransformer implements QueryTransformer {

    protected final ChatModel chatModel;
    protected final PromptTemplate promptTemplate;
    protected final StringRedisTemplate redisTemplate;

    /**
     * 会话ID，用于获取历史对话和回写改写结果
     */
    private final String sessionId;

    /**
     * 进度回调，用于流式返回前端进度信息
     */
    private final Consumer<String> progressCallback;

    private static final String SESSION_PREFIX = "rag:session:";
    private static final String TRANSFORM_PREFIX = "rag:transform:";
    private static final int SESSION_TTL_MINUTES = 30;

    /**
     * 通用场景改写 Prompt 模板
     */
    private static final PromptTemplate GENERAL_PROMPT = PromptTemplate.from("""
            你是一个智能助手，你需要对用户的问题进行改写，使得改写后的问题在查询知识库时有更好的检索效果。
            请删除任何无关信息，确保查询简洁明了、具体明确。
            
            ### 改写策略
            
            1、**简洁改写**。删除无意义的语气词、修饰词或者重复的词语。改写规则：删除冗余词语使其更适合搜索引擎检索，疑问句转成陈述句。
            
            2、**抽象概念改写**。如果用户的问题是具体的细节问题，需要改写成更基础、更简洁、更抽象的问题。例如："我的电脑每次开机都很慢怎么办" 改写为 "电脑开机速度慢优化方法"。
            
            3、**错别字改写**。纠正用户问题中的错别字或拼音错误。
            
            4、**关键信息提取**。如果用户提到了具体的实体信息（产品名、品牌、型号等），需要将其标准化提取。
            
            5、**上下文补全**。结合历史对话和最新提问，识别出所有相关的细节、术语和上下文信息。最后，将这条提问重新组织成一个清晰、简洁且独立完整的格式，以便于进行信息检索。
            
            上面是5种改写策略，需要逐一使用最终给出一个统一的改写结果。直接输出改写后的结果，不需要输出思考过程及额外的多余内容。如果不需要改写，则直接输出原问题即可。
            
            ### 示例
            
            Input：我如果想买一台笔记本电脑的话，大概需要多少钱啊
            Output：笔记本电脑价格指南
            
            Input：我的手机电池不耐用了，多久换一次？
            Output：手机电池更换周期
            
            Input：我的电脑运行有点卡顿是怎么回事
            Output：电脑运行卡顿故障排查
            
            Input：iPhone 15续航多少
            Output：iPhone 15电池续航时间
            
            Input：保修期还有多久？
            Output：产品保修期查询
            
            ### 历史对话内容
            {{chatMemory}}
            
            ### 用户问题：
            
            {{query}}
            
            ### 要求：
            非常重要的一点是：你只需要提供重新组织后的提问，不要包含任何其他内容！绝对不要在提问前添加任何多余的文字！
            """);

    /**
     * 构造函数 - 基础版本
     *
     * @param chatModel      聊天模型
     * @param redisTemplate  Redis模板（用于获取会话历史）
     * @param sessionId      会话ID
     */
    public RagDemoQueryTransformer(ChatModel chatModel, StringRedisTemplate redisTemplate, String sessionId) {
        this(chatModel, redisTemplate, sessionId, null);
    }

    /**
     * 构造函数 - 带进度回调
     *
     * @param chatModel         聊天模型
     * @param redisTemplate     Redis模板（用于获取会话历史）
     * @param sessionId         会话ID
     * @param progressCallback  进度回调函数
     */
    public RagDemoQueryTransformer(ChatModel chatModel, StringRedisTemplate redisTemplate, 
                                   String sessionId, Consumer<String> progressCallback) {
        this(chatModel, GENERAL_PROMPT, redisTemplate, sessionId, progressCallback);
    }

    /**
     * 构造函数 - 完整版本（可自定义Prompt）
     *
     * @param chatModel         聊天模型
     * @param promptTemplate    Prompt模板
     * @param redisTemplate     Redis模板
     * @param sessionId         会话ID
     * @param progressCallback  进度回调函数
     */
    public RagDemoQueryTransformer(ChatModel chatModel, PromptTemplate promptTemplate,
                                   StringRedisTemplate redisTemplate, String sessionId, 
                                   Consumer<String> progressCallback) {
        this.chatModel = ensureNotNull(chatModel, "chatModel");
        this.promptTemplate = ensureNotNull(promptTemplate, "promptTemplate");
        this.redisTemplate = ensureNotNull(redisTemplate, "redisTemplate");
        this.sessionId = sessionId;
        this.progressCallback = progressCallback;
    }

    @Override
    public Collection<Query> transform(Query query) {
        // 发送进度：开始问题改写
        if (progressCallback != null) {
            progressCallback.accept("[PROGRESS]:正在优化您的问题...");
            log.info("[PROGRESS]:正在优化您的问题...");
        }

        log.info("开始问题改写, 原始问题: {}", query.text());
        
        // 获取历史对话记忆
        List<ChatMessage> chatMemory = getChatMemory();
        
        Stopwatch stopwatch = Stopwatch.createStarted();
        String response = chatModel.chat(createPrompt(query, format(chatMemory)).text());
        log.info("问题改写完成, 改写结果: {}, 耗时: {} ms", response, stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));

        // 构造增强查询（附加会话ID、当前时间等上下文信息）
        String newQuery = response + " [sessionId=" + sessionId + ", timestamp=" + LocalDateTime.now() + "]";

        Query transformedQuery = query.metadata() == null
                ? Query.from(newQuery)
                : Query.from(newQuery, query.metadata());
        
        log.info("查询转换成功, 原始查询: {}, 转换后查询: {}", query.text(), transformedQuery.text());

        // 异步回写改写结果到 Redis（可选）
        if (sessionId != null && !sessionId.isEmpty()) {
            Thread.ofVirtual().name("query-transform-" + sessionId).start(() -> {
                try {
                    saveTransformResult(sessionId, response);
                    log.info("改写结果已保存到Redis: sessionId={}, transformContent={}", sessionId, response);
                } catch (Exception e) {
                    log.warn("改写结果保存失败: sessionId={}", sessionId, e);
                }
            });
        }

        // 返回单个改写后的查询（也可以返回多个变体进行混合检索）
        return singletonList(transformedQuery);
    }

    /**
     * 从 Redis 获取会话历史
     *
     * @return 历史对话消息列表
     */
    protected List<ChatMessage> getChatMemory() {
        if (sessionId == null || sessionId.isEmpty()) {
            return Collections.emptyList();
        }

        String key = SESSION_PREFIX + sessionId;
        List<String> messages = redisTemplate.opsForList().range(key, -10, -1);

        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (String msg : messages) {
            if (msg.startsWith("user:")) {
                chatMessages.add(UserMessage.from(msg.substring(5)));
            } else if (msg.startsWith("ai:")) {
                chatMessages.add(AiMessage.from(msg.substring(3)));
            }
        }

        return chatMessages;
    }

    /**
     * 保存改写结果到 Redis
     *
     * @param sessionId       会话ID
     * @param transformResult 改写结果
     */
    protected void saveTransformResult(String sessionId, String transformResult) {
        String key = TRANSFORM_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, transformResult, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 创建 Prompt
     *
     * @param query      原始查询
     * @param chatMemory 格式化的历史对话
     * @return Prompt 对象
     */
    protected Prompt createPrompt(Query query, String chatMemory) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("query", query.text());
        variables.put("chatMemory", chatMemory);
        return promptTemplate.apply(variables);
    }

    /**
     * 格式化历史对话
     *
     * @param chatMemory 历史对话列表
     * @return 格式化后的字符串
     */
    protected String format(List<ChatMessage> chatMemory) {
        return chatMemory.stream()
                .map(this::format)
                .filter(Objects::nonNull)
                .collect(joining("\n"));
    }

    /**
     * 格式化单条消息
     *
     * @param message 消息对象
     * @return 格式化后的字符串
     */
    protected String format(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "User: " + userMessage.singleText();
        } else if (message instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                return null;
            }
            return "AI: " + aiMessage.text();
        } else {
            return null;
        }
    }
}
