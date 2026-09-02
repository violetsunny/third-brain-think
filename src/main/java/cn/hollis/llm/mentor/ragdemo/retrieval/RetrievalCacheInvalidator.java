package cn.hollis.llm.mentor.ragdemo.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索缓存失效服务
 * 当知识库内容变更（嵌入完成/文档删除）时，主动清除对应检索缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalCacheInvalidator {

    private static final String KEY_PREFIX = "rag-demo:retrieval:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 清除指定知识库类型相关的所有检索缓存
     * 使用 Redis SCAN 避免阻塞
     *
     * @param knowledgeBaseType 知识库类型（目前清除所有检索缓存）
     */
    public void invalidateByKnowledgeBaseType(String knowledgeBaseType) {
        try {
            String pattern = KEY_PREFIX + "*";
            List<String> keys = new ArrayList<>();

            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }

            if (keys.isEmpty()) {
                log.debug("无检索缓存需要清除: knowledgeBaseType={}", knowledgeBaseType);
                return;
            }

            redisTemplate.delete(keys);
            log.info("检索缓存已清除: knowledgeBaseType={}, deletedKeys={}", knowledgeBaseType, keys.size());
        } catch (Exception e) {
            log.warn("检索缓存清除失败，不影响主流程: knowledgeBaseType={}, error={}", knowledgeBaseType, e.getMessage());
        }
    }
}
