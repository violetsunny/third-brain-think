package top.kdla.framework.llm.mentor.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置
 * 为嵌入任务提供独立线程池
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${rag.embedding.executor.core-size:2}")
    private int coreSize;

    @Value("${rag.embedding.executor.max-size:4}")
    private int maxSize;

    @Value("${rag.embedding.executor.queue-capacity:100}")
    private int queueCapacity;

    @Bean("embeddingExecutor")
    public Executor embeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("embedding-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("嵌入线程池初始化: core={}, max={}, queue={}", coreSize, maxSize, queueCapacity);
        return executor;
    }
}
