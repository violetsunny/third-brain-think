package cn.hollis.llm.mentor.ragdemo.retrieval;

import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rerank API 限流保护
 * 基于 Guava RateLimiter，防止超出第三方 API QPS 限制
 */
@Slf4j
@Component
public class RerankRateLimiter {

    private final RateLimiter rateLimiter;

    public RerankRateLimiter(@Value("${rag.rerank.rate-limit-qps:2.0}") double qps) {
        this.rateLimiter = RateLimiter.create(qps);
        log.info("Rerank 限流器初始化: qps={}", qps);
    }

    /**
     * 尝试获取令牌（非阻塞）
     *
     * @return true 表示获取成功，可以调用 Rerank API；false 表示超限，需要降级
     */
    public boolean tryAcquire() {
        return rateLimiter.tryAcquire();
    }
}
