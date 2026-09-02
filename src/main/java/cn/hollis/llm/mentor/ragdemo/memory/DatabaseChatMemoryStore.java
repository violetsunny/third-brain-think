package cn.hollis.llm.mentor.ragdemo.memory;

import cn.hollis.llm.mentor.ragdemo.entity.ChatMessage;
import cn.hollis.llm.mentor.ragdemo.service.ChatMessageService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 持久化 Chat Memory 存储器
 * Redis 热缓存 + MySQL 冷存储双写模式
 * - 读：先查 Redis，miss 时查 MySQL 并回填 Redis
 * - 写：同步写 Redis + MySQL
 * - evictCache：只删 Redis，不动 MySQL（用于防意图污染）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageService chatMessageService;
    private final StringRedisTemplate redisTemplate;

    @Value("${rag.chat.memory.redis-key-prefix:rag-demo:chat-memory:}")
    private String redisKeyPrefix;

    @Value("${rag.chat.memory.ttl-hours:1}")
    private long ttlHours;

    @Value("${rag.chat.memory.window-size:10}")
    private int windowSize;

    // ─────────────────────────────────────────────────────────────
    // ChatMemoryStore interface
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        String key = redisKeyPrefix + conversationId;

        // 1. Try Redis first
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("Chat memory cache hit: {}", conversationId);
            return deserialize(cached);
        }

        // 2. Fallback to MySQL
        log.debug("Chat memory cache miss, loading from MySQL: {}", conversationId);
        List<ChatMessage> dbMessages = chatMessageService.getMessagesByConversationId(conversationId);
        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages = toLC4JMessages(dbMessages);

        // Apply window limit
        if (lc4jMessages.size() > windowSize) {
            lc4jMessages = lc4jMessages.subList(lc4jMessages.size() - windowSize, lc4jMessages.size());
        }

        // Write back to Redis
        if (!lc4jMessages.isEmpty()) {
            redisTemplate.opsForValue().set(key, serialize(lc4jMessages), ttlHours, TimeUnit.HOURS);
        }

        return lc4jMessages;
    }

    @Override
    public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        String conversationId = memoryId.toString();
        String key = redisKeyPrefix + conversationId;

        // Write to Redis
        redisTemplate.opsForValue().set(key, serialize(messages), ttlHours, TimeUnit.HOURS);
        log.debug("Chat memory updated in Redis: {} ({} messages)", conversationId, messages.size());
        // Note: MySQL persistence is handled by ChatController saving messages explicitly.
        // DatabaseChatMemoryStore only writes the LangChain4j in-memory representation to Redis.
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        evictCache(conversationId);
        // MySQL deletion is done via ChatMessageService.deleteMessagesByConversationId()
        // which is called from ChatController
        log.debug("Chat memory deleted for conversation: {}", conversationId);
    }

    // ─────────────────────────────────────────────────────────────
    // Extra: evictCache — only clears Redis, leaves MySQL intact
    // ─────────────────────────────────────────────────────────────

    /**
     * 只清除 Redis 缓存，不删 MySQL 数据。
     * 在每次用户提问前调用，防止上一轮 RAG 引用内容污染意图识别。
     */
    public void evictCache(String conversationId) {
        String key = redisKeyPrefix + conversationId;
        redisTemplate.delete(key);
        log.debug("Evicted chat memory cache for conversation: {}", conversationId);
    }

    // ─────────────────────────────────────────────────────────────
    // Serialization helpers
    // ─────────────────────────────────────────────────────────────

    private String serialize(List<dev.langchain4j.data.message.ChatMessage> messages) {
        List<SerializedMessage> dtos = messages.stream()
                .map(m -> new SerializedMessage(m.type().name(), toText(m)))
                .collect(Collectors.toList());
        return JSON.toJSONString(dtos);
    }

    private List<dev.langchain4j.data.message.ChatMessage> deserialize(String json) {
        try {
            List<SerializedMessage> dtos = JSON.parseObject(json, new TypeReference<>() {});
            return dtos.stream().map(this::fromDto).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to deserialize chat memory, returning empty: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String toText(dev.langchain4j.data.message.ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return um.singleText();
        } else if (msg instanceof AiMessage am) {
            return am.text();
        } else if (msg instanceof SystemMessage sm) {
            return sm.text();
        }
        return msg.toString();
    }

    private dev.langchain4j.data.message.ChatMessage fromDto(SerializedMessage dto) {
        return switch (ChatMessageType.valueOf(dto.type())) {
            case USER -> UserMessage.from(dto.text());
            case AI -> AiMessage.from(dto.text());
            case SYSTEM -> SystemMessage.from(dto.text());
            default -> UserMessage.from(dto.text());
        };
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLC4JMessages(List<ChatMessage> dbMessages) {
        return dbMessages.stream()
                .map(m -> {
                    if ("USER".equals(m.getType())) {
                        return (dev.langchain4j.data.message.ChatMessage) UserMessage.from(m.getContent());
                    } else {
                        return (dev.langchain4j.data.message.ChatMessage) AiMessage.from(m.getContent());
                    }
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────
    // Inner DTO
    // ─────────────────────────────────────────────────────────────
    private record SerializedMessage(String type, String text) {}
}
