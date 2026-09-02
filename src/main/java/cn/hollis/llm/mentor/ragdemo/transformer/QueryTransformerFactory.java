package cn.hollis.llm.mentor.ragdemo.transformer;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * RagDemo 查询转换器工厂
 * 用于动态创建带 sessionId 和 progressCallback 的查询转换器实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryTransformerFactory {

    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建查询转换器实例
     *
     * @param sessionId         会话ID（可为null）
     * @param progressCallback  进度回调函数（可为null）
     * @return 查询转换器实例
     */
    public QueryTransformer create(String sessionId, Consumer<String> progressCallback) {
        log.debug("创建查询转换器: sessionId={}, hasProgressCallback={}", 
                  sessionId, progressCallback != null);
        
        return new RagDemoQueryTransformer(
                chatModel, 
                redisTemplate, 
                sessionId, 
                progressCallback
        );
    }

    /**
     * 创建不带进度回调的查询转换器
     *
     * @param sessionId 会话ID
     * @return 查询转换器实例
     */
    public QueryTransformer create(String sessionId) {
        return create(sessionId, null);
    }

    /**
     * 创建默认查询转换器（无会话、无进度回调）
     *
     * @return 查询转换器实例
     */
    public QueryTransformer createDefault() {
        return create(null, null);
    }
}
