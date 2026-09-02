package top.kdla.framework.llm.mentor.rag.transformer;

import com.alibaba.fastjson2.JSON;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 查询转换服务
 * 支持多种查询改写策略：富化、分解、多样化、回溯提示
 * 集成Redis会话记忆
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTransformerService {

    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;

    private final QueryComplexityClassifier complexityClassifier = new QueryComplexityClassifier();

    private static final String SESSION_PREFIX = "rag:session:";
    private static final int SESSION_TTL_MINUTES = 30;

    // ==================== 保真原则（所有改写策略共享） ====================

    /**
     * 查询改写保真原则 — 借鉴 gogo-agent 动作词保真设计，防止改写后检索语义漂移。
     * <p>核心约束：保留实体名/数值/专业术语，禁止引入新概念，禁止泛化为无关问题。
     */
    private static final String FIDELITY_RULES = """

            ## 保真原则（必须严格遵守）
            1. 保留原始查询中的所有实体名称、产品名、品牌名、型号、数值、日期、专业术语
            2. 不得引入原文中未出现的新实体或新概念
            3. 不得将具体问题泛化为语义无关的抽象问题
            4. 改写后的查询必须与原始查询保持相同的检索意图
            5. 如果原始查询已经足够清晰，直接输出原问题，不要强行改写
            """;

    // ==================== Prompt模板 ====================

    /**
     * 问题分解 - 将复杂问题拆解为多个独立子问题
     */
    private static final String DECOMPOSE_PROMPT = """
            # 角色
            你是一名专业的查询逻辑分析专家。
            
            # 任务
            将给定的"用户原始问题"分解为一系列**相互独立、逻辑清晰**，且可单独用于检索的子查询列表。
            你的输出必须是一个标准的JSON数组格式。
            
            # 用户原始问题
            {QUESTION}
            """ + FIDELITY_RULES + """
            
            # 输出格式要求 (JSON Array)
            [
              "子查询1",
              "子查询2",
              "子查询3"
            ]
            
            （不强制要求数组元素个数，可根据真实情况输出，至少保留1个）
            
            # 输出
            请直接输出JSON数组，不要包含解释或多余的文字。""";

    /**
     * 问题富化 - 结合对话历史补充上下文
     */
    private static final String ENRICH_PROMPT = """
            # 角色
            你是一个专业的问题重写优化器。
            
            # 任务
            根据提供的"对话历史"和"用户原始问题"，重写为一个独立、完整、且包含所有必要背景信息的新查询，用于RAG检索。
            
            ## 对话历史：
            {CHAT_HISTORY}
            
            ## 原始问题：
            {QUESTION}
            """ + FIDELITY_RULES + """
            
            # 输出
            输出富化过后的新问题，不要包含多余的解释性内容""";

    /**
     * 问题多样化 - 生成多个语义相同的查询变体
     */
    private static final String DIVERSIFY_PROMPT = """
            # 角色
            你是一名专业的语义扩展专家。
            
            # 任务
            为给定的"原始问题"生成**3个**语义相同但**措辞完全不同、且利于检索**的查询变体，以提高检索的召回率。
            你的输出必须是一个标准的JSON数组格式。
            
            # 原始问题
            {QUESTION}
            """ + FIDELITY_RULES + """
            
            # 输出格式要求 (JSON Array)
            [
              "变体1",
              "变体2",
              "变体3"
            ]
            
            # 输出
            直接输出JSON数组，不要包含多余的解释性内容""";

    /**
     * 问题回退 - Step-Back Prompting，抽象出本质问题
     */
    private static final String STEP_BACK_PROMPT = """
            # 角色
            你是一个擅长抽象思维和原理推理的专家。
            
            # 任务
            请根据用户提出的具体问题，先"后退一步"，将其转化为一个更通用、更本质的问题，聚焦于背后的原理、规律、概念或一般性知识，而不是具体细节。
            
            # 原始问题
            {QUESTION}
            """ + FIDELITY_RULES + """
            
            # 输出
            请只输出改写后的"后退问题"，不要解释，不要包含原始问题，也不要回答它。""";

    // ==================== 核心方法 ====================

    /**
     * 保存用户消息到会话历史
     *
     * @param sessionId 会话ID
     * @param message   用户消息
     */
    public void saveUserMessage(String sessionId, String message) {
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.opsForList().rightPush(key, "user:" + message);
        redisTemplate.expire(key, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("Saved user message to session: {}", sessionId);
    }

    /**
     * 保存AI回复到会话历史
     *
     * @param sessionId 会话ID
     * @param message   AI回复
     */
    public void saveAiMessage(String sessionId, String message) {
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.opsForList().rightPush(key, "ai:" + message);
        redisTemplate.expire(key, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("Saved AI message to session: {}", sessionId);
    }

    /**
     * 获取会话历史
     *
     * @param sessionId 会话ID
     * @param maxMessages 最大消息数量
     * @return 格式化的对话历史字符串
     */
    public String getChatHistory(String sessionId, int maxMessages) {
        String key = SESSION_PREFIX + sessionId;
        List<String> messages = redisTemplate.opsForList().range(key, -maxMessages, -1);
        
        if (messages == null || messages.isEmpty()) {
            return "无";
        }

        StringBuilder history = new StringBuilder();
        for (String msg : messages) {
            if (msg.startsWith("user:")) {
                history.append("用户: ").append(msg.substring(5)).append("\n");
            } else if (msg.startsWith("ai:")) {
                history.append("助手: ").append(msg.substring(3)).append("\n");
            }
        }
        
        return history.toString();
    }

    /**
     * 清除会话历史
     *
     * @param sessionId 会话ID
     */
    public void clearSession(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        redisTemplate.delete(key);
        log.info("Cleared session: {}", sessionId);
    }

    // ==================== 查询改写策略 ====================

    /**
     * 问题分解
     *
     * @param question 原始问题
     * @return 分解后的子问题列表
     */
    public List<String> decompose(String question) {
        log.info("========== 进入问题分解流程 ==========");
        log.info("原始问题: {}", question);
        
        try {
            String prompt = DECOMPOSE_PROMPT.replace("{QUESTION}", question);
            String result = chatModel.chat(prompt);
            
            log.info("问题分解完成，结果: {}", result);
            
            // 解析JSON数组
            List<String> queries = JSON.parseArray(result, String.class);
            return queries != null && !queries.isEmpty() ? queries : List.of(question);
            
        } catch (Exception e) {
            log.error("问题分解失败", e);
            return List.of(question);
        }
    }

    /**
     * 问题富化 - 结合会话历史
     *
     * @param sessionId 会话ID
     * @param question  原始问题
     * @return 富化后的问题
     */
    public String enrich(String sessionId, String question) {
        log.info("========== 进入问题富化流程 ==========");
        
        String chatHistory = getChatHistory(sessionId, 10);
        log.info("对话历史: {}", chatHistory);
        log.info("原始问题: {}", question);
        
        try {
            String prompt = ENRICH_PROMPT
                    .replace("{CHAT_HISTORY}", chatHistory)
                    .replace("{QUESTION}", question);
            
            String result = chatModel.chat(prompt);
            log.info("问题富化完成，结果: {}", result);
            
            return result != null && !result.trim().isEmpty() ? result.trim() : question;
            
        } catch (Exception e) {
            log.error("问题富化失败", e);
            return question;
        }
    }

    /**
     * 问题多样化
     *
     * @param question 原始问题
     * @return 多样化的查询变体列表
     */
    public List<String> diversify(String question) {
        log.info("========== 进入问题多样化流程 ==========");
        log.info("原始问题: {}", question);
        
        try {
            String prompt = DIVERSIFY_PROMPT.replace("{QUESTION}", question);
            String result = chatModel.chat(prompt);
            
            log.info("问题多样化完成，结果: {}", result);
            
            // 解析JSON数组
            List<String> variations = JSON.parseArray(result, String.class);
            return variations != null && !variations.isEmpty() ? variations : List.of(question);
            
        } catch (Exception e) {
            log.error("问题多样化失败", e);
            return List.of(question);
        }
    }

    /**
     * 问题回退 - Step-Back Prompting
     *
     * @param question 原始问题
     * @return 回退后的抽象问题
     */
    public String stepBack(String question) {
        log.info("========== 进入问题回退流程 ==========");
        log.info("原始问题: {}", question);
        
        try {
            String prompt = STEP_BACK_PROMPT.replace("{QUESTION}", question);
            String result = chatModel.chat(prompt);
            
            log.info("问题回退完成，结果: {}", result);
            
            return result != null && !result.trim().isEmpty() ? result.trim() : question;
            
        } catch (Exception e) {
            log.error("问题回退失败", e);
            return question;
        }
    }

    // ==================== 组合策略 ====================

    /**
     * 完整查询重写流程
     * 策略：回退 -> 分解 -> 多样化
     *
     * <p>L1 短路：简单查询（无指代词、单意图、实体明确）直接跳过改写，省去 3+ 次 LLM 调用。
     *
     * @param question 原始问题
     * @return 重写后的查询列表
     */
    public List<String> rewriteQuery(String question) {
        log.info("========== 进入完整查询重写流程 ==========");
        log.info("原始问题: {}", question);

        if (shouldShortCircuit(question)) {
            return List.of(question);
        }

        try {
            // 1. Step-Back: 回退到更抽象的问题
            String stepBackQuery = stepBack(question);
            log.info("Step-Back结果: {}", stepBackQuery);
            
            // 2. Decompose: 分解为多个子问题
            List<String> decomposedQueries = decompose(stepBackQuery);
            log.info("分解结果: {} 个子问题", decomposedQueries.size());
            
            // 3. Diversify: 对每个子问题进行多样化
            List<String> finalQueries = new ArrayList<>();
            for (String subQuery : decomposedQueries) {
                List<String> variations = diversify(subQuery);
                finalQueries.addAll(variations);
            }
            
            // 确保至少有一个查询
            if (finalQueries.isEmpty()) {
                finalQueries.add(question);
            }
            
            log.info("========== 查询重写完成，最终查询列表: {} ==========", finalQueries);
            return finalQueries;
            
        } catch (Exception e) {
            log.error("查询重写失败，返回原始问题", e);
            return List.of(question);
        }
    }

    /**
     * 带会话记忆的查询重写
     *
     * <p>L1 短路：简单查询跳过改写管道。但如果查询含指代词（判定为 COMPLEX），
     * 仍需 enrich 消解上下文，保真原则防止消解后语义漂移。
     *
     * @param sessionId 会话ID
     * @param question  原始问题
     * @return 重写后的查询列表
     */
    public List<String> rewriteQueryWithMemory(String sessionId, String question) {
        log.info("========== 进入带会话记忆的查询重写流程 ==========");
        log.info("会话ID: {}, 原始问题: {}", sessionId, question);

        if (shouldShortCircuit(question)) {
            return List.of(question);
        }

        try {
            // 1. Enrich: 结合会话历史富化问题
            String enrichedQuery = enrich(sessionId, question);
            log.info("富化结果: {}", enrichedQuery);
            
            // 2. Step-Back: 回退到更抽象的问题
            String stepBackQuery = stepBack(enrichedQuery);
            log.info("Step-Back结果: {}", stepBackQuery);
            
            // 3. Decompose: 分解为多个子问题
            List<String> decomposedQueries = decompose(stepBackQuery);
            log.info("分解结果: {} 个子问题", decomposedQueries.size());
            
            // 4. Diversify: 对每个子问题进行多样化
            List<String> finalQueries = new ArrayList<>();
            for (String subQuery : decomposedQueries) {
                List<String> variations = diversify(subQuery);
                finalQueries.addAll(variations);
            }
            
            // 确保至少有一个查询
            if (finalQueries.isEmpty()) {
                finalQueries.add(enrichedQuery);
            }
            
            log.info("========== 带记忆的查询重写完成，最终查询列表: {} ==========", finalQueries);
            return finalQueries;
            
        } catch (Exception e) {
            log.error("带记忆的查询重写失败，返回原始问题", e);
            return List.of(question);
        }
    }

    // ==================== L1 短路 ====================

    /**
     * L1 规则短路：简单查询跳过改写管道，省去 3+ 次 LLM 调用。
     *
     * @param question 原始问题
     * @return true 表示应短路，调用方直接返回 List.of(question)
     */
    private boolean shouldShortCircuit(String question) {
        QueryComplexity complexity = complexityClassifier.classify(question);
        if (complexity == QueryComplexity.SIMPLE) {
            log.info("查询判定为 SIMPLE，短路跳过改写管道: {}", question);
            return true;
        }
        return false;
    }
}
