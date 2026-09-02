package cn.hollis.llm.mentor.ragdemo.retrieval;

import cn.hollis.llm.mentor.ragdemo.retrieval.SearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 检索结果缓存服务
 * 基于 Redis，以 SHA-256(query+kbType) 为缓存键，FastJSON2 序列化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCacheService {

    private static final String KEY_PREFIX = "rag-demo:retrieval:";

    private final StringRedisTemplate redisTemplate;

    @Value("${rag.cache.retrieval-ttl-seconds:300}")
    private long ttlSeconds;

    /**
     * 查询缓存
     *
     * @param query             用户查询
     * @param knowledgeBaseType 知识库类型
     * @return 命中时返回缓存结果，未命中返回 Optional.empty()
     */
    public Optional<List<SearchResult>> get(String query, String knowledgeBaseType) {
        try {
            String key = buildKey(query, knowledgeBaseType);
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                return Optional.empty();
            }
            List<SearchResult> results = JSON.parseObject(cached, new TypeReference<List<SearchResult>>() {});
            log.debug("检索缓存命中: key={}, count={}", key, results.size());
            return Optional.of(results);
        } catch (Exception e) {
            log.warn("检索缓存读取失败，降级走实际检索: query={}, error={}", query, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入缓存
     *
     * @param query             用户查询
     * @param knowledgeBaseType 知识库类型
     * @param results           检索结果
     */
    public void put(String query, String knowledgeBaseType, List<SearchResult> results) {
        try {
            String key = buildKey(query, knowledgeBaseType);
            String value = JSON.toJSONString(results);
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            log.debug("检索结果写入缓存: key={}, count={}", key, results.size());
        } catch (Exception e) {
            log.warn("检索缓存写入失败，不影响检索结果: query={}, error={}", query, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private String buildKey(String query, String knowledgeBaseType) {
        // 归一化：trim + toLowerCase，消除大小写/首尾空格差异（design.md D1）
        String normalizedQuery = query.trim().toLowerCase();
        String raw = normalizedQuery + "::" + knowledgeBaseType;
        return KEY_PREFIX + sha256(raw);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: 直接用原始字符串（不应发生）
            log.warn("SHA-256 计算失败，使用原始 key", e);
            return input.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        }
    }
}
