package top.kdla.framework.llm.mentor.rag.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import top.kdla.framework.llm.mentor.rag.embedding.VectorStoreService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 模型配置
 * 支持云端API (DashScope) 和本地模型 (BGE)
 *
 * <p>注意：不再定义 {@code chatMemoryProvider} Bean，
 * 该 Bean 由 {@link RagConfiguration} 以 DatabaseChatMemoryStore（Redis + MySQL 双写）
 * 方式统一提供，此处移除重复定义以消除 Spring Bean 冲突。
 *
 * <p>向量存储 Bean（MilvusEmbeddingStore / ElasticsearchEmbeddingStore）由
 * {@link VectorStoreService} 负责管理，
 * 此配置类只负责模型初始化，不依赖具体向量存储类型。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai")
public class LangChain4jConfig {

    private ChatModelConfig chatModel;
    private EmbeddingModelConfig embeddingModel;

    @Value("${rag.embedding.provider:dashscope}")
    private String embeddingProvider; // dashscope 或 bge-local

    @Data
    public static class ChatModelConfig {
        private String apiKey;
        private String modelName = "qwen-plus";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private Double temperature = 0.7;
        private Duration timeout = Duration.ofSeconds(60);
    }

    @Data
    public static class EmbeddingModelConfig {
        private String apiKey;
        private String modelName = "text-embedding-v3";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private Integer dimensions = 1536;
        private Integer maxSegmentsPerBatch = 10;
        private Duration timeout = Duration.ofSeconds(60);
    }

    @Bean
    public ChatModel chatModel() {
        log.info("Initializing ChatModel: {}", chatModel.getModelName());
        return OpenAiChatModel.builder()
                .apiKey(chatModel.getApiKey())
                .modelName(chatModel.getModelName())
                .baseUrl(chatModel.getBaseUrl())
                .temperature(chatModel.getTemperature())
                .timeout(chatModel.getTimeout())
                .build();
    }

    /**
     * 流式聊天模型（用于 SSE 流式响应）
     */
    @Bean
    public StreamingChatModel openAiStreamingChatModel() {
        log.info("Initializing StreamingChatModel: {}", chatModel.getModelName());
        return OpenAiStreamingChatModel.builder()
                .apiKey(chatModel.getApiKey())
                .modelName(chatModel.getModelName())
                .baseUrl(chatModel.getBaseUrl())
                .temperature(chatModel.getTemperature())
                .timeout(chatModel.getTimeout())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Initializing EmbeddingModel with provider: {}", embeddingProvider);

        if ("bge-local".equalsIgnoreCase(embeddingProvider)) {
            // 使用本地BGE模型 (ONNX, 离线可用)
            // 注意: 需要取消pom.xml中langchain4j-embeddings-bge-small-zh-v15的注释并重新加载Maven依赖
            log.warn("BGE local model is not yet enabled. Please uncomment the dependency in pom.xml and reload Maven.");
            log.info("Falling back to DashScope Embedding API");
        }

        // 默认使用DashScope云端API
        log.info("Using DashScope Embedding API: {}", embeddingModel.getModelName());
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingModel.getApiKey())
                .modelName(embeddingModel.getModelName())
                .baseUrl(embeddingModel.getBaseUrl())
                .dimensions(embeddingModel.getDimensions())
                .maxSegmentsPerBatch(embeddingModel.getMaxSegmentsPerBatch())
                .timeout(embeddingModel.getTimeout())
                .build();
    }
}

