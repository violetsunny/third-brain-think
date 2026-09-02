package top.kdla.framework.llm.mentor.rag.transformer;

import top.kdla.framework.llm.mentor.rag.memory.DatabaseChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 上下文感知查询改写 Transformer
 * 读取近 K 轮对话历史，结合当前查询改写为完整独立的查询语句
 * 消除指代词歧义（如"它"、"这个"等）
 *
 * 通过 rag.context-aware.enabled=true 激活
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.context-aware.enabled", havingValue = "true")
public class ContextAwareQueryTransformer implements QueryTransformer {

    /**
     * ThreadLocal 存储当前请求的 conversationId
     * 由 ChatController 在请求入口写入，此处读取
     */
    public static final ThreadLocal<String> CONVERSATION_ID_HOLDER = new ThreadLocal<>();

    private final DatabaseChatMemoryStore chatMemoryStore;
    private final ChatModel chatModel;
    private final int historyTurns;

    public ContextAwareQueryTransformer(DatabaseChatMemoryStore chatMemoryStore,
                                        ChatModel chatModel,
                                        @Value("${rag.context-aware.history-turns:3}") int historyTurns) {
        this.chatMemoryStore = chatMemoryStore;
        this.chatModel = chatModel;
        this.historyTurns = historyTurns;
    }

    @Override
    public Collection<Query> transform(Query query) {
        String originalText = query.text();
        String conversationId = CONVERSATION_ID_HOLDER.get();

        if (conversationId == null || conversationId.isBlank()) {
            log.debug("无 conversationId，跳过上下文改写: query={}", originalText);
            return Collections.singletonList(query);
        }

        try {
            List<ChatMessage> history = chatMemoryStore.getMessages(conversationId);
            if (history == null || history.isEmpty()) {
                log.debug("无历史消息，跳过上下文改写: conversationId={}", conversationId);
                return Collections.singletonList(query);
            }

            // 取近 historyTurns * 2 条（每轮包含 user + ai）
            int limit = historyTurns * 2;
            List<ChatMessage> recentHistory = history.size() > limit
                    ? history.subList(history.size() - limit, history.size())
                    : history;

            String prompt = buildRewritePrompt(recentHistory, originalText);
            String rewritten = chatModel.chat(prompt).trim();

            if (rewritten.isBlank() || rewritten.equals(originalText)) {
                return Collections.singletonList(query);
            }

            log.debug("上下文改写完成: original={}, rewritten={}", originalText, rewritten);
            return Collections.singletonList(Query.from(rewritten, query.metadata()));

        } catch (Exception e) {
            log.warn("上下文改写失败，降级返回原始查询: query={}, error={}", originalText, e.getMessage());
            return Collections.singletonList(query);
        }
    }

    private String buildRewritePrompt(List<ChatMessage> history, String currentQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是用户与助手的历史对话摘要：\n");
        for (ChatMessage msg : history) {
            String role = msg.type().name().equals("USER") ? "用户" : "助手";
            String text = extractText(msg);
            sb.append(role).append(": ").append(text).append("\n");
        }
        sb.append("\n当前用户查询: ").append(currentQuery);
        sb.append("\n\n请将当前查询改写为一个完整、独立的查询语句，消除指代词歧义，只返回改写后的查询，不要添加任何解释。");
        return sb.toString();
    }

    private String extractText(ChatMessage msg) {
        if (msg instanceof dev.langchain4j.data.message.UserMessage um) {
            return um.singleText();
        } else if (msg instanceof dev.langchain4j.data.message.AiMessage am) {
            return am.text();
        }
        return msg.toString();
    }
}
