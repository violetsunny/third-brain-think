package top.kdla.framework.llm.mentor.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import top.kdla.framework.llm.mentor.rag.retrieval.KeywordContentRetriever;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.StringReader;

/**
 * Elasticsearch 配置类（可选组件）
 *
 * <p>仅当 {@code elasticsearch.enable=true} 时加载。
 * 未激活时，ES 相关 Bean 均不注册，启动不尝试连接 ES。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "elasticsearch.enable", havingValue = "true")
public class ElasticsearchConfig {

    @Value("${elasticsearch.uris:http://localhost:9200}")
    private String esUris;

    @Value("${elasticsearch.index-name:rag_demo_index}")
    private String indexName;

    private ElasticsearchClient client;

    /**
     * 创建 Elasticsearch Client。
     *
     * <p>连接失败时抛出 {@code RuntimeException}，阻止应用启动。
     * 这是预期行为：用户显式设置 {@code elasticsearch.enable=true} 意味着声明 ES 可用，
     * 若实际不可达，应尽早暴露而非静默降级（静默降级会导致关键词检索无声失效）。
     * 如需禁用 ES，应将 {@code elasticsearch.enable} 改回 {@code false}。
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        try {
            // 解析 URI
            String uri = esUris.split(",")[0]; // 取第一个地址
            java.net.URI parsedUri = java.net.URI.create(uri);

            String scheme = parsedUri.getScheme() != null ? parsedUri.getScheme() : "http";
            String host = parsedUri.getHost() != null ? parsedUri.getHost() : "localhost";
            int port = parsedUri.getPort() > 0 ? parsedUri.getPort() : 9200;

            log.info("Initializing Elasticsearch client: {}://{}:{}", scheme, host, port);

            // 创建 RestClient
            RestClient restClient = RestClient.builder(
                    new HttpHost(host, port, scheme)
            ).build();

            // 创建 Transport
            RestClientTransport transport = new RestClientTransport(
                    restClient,
                    new JacksonJsonpMapper()
            );

            // 创建 Elasticsearch Client
            this.client = new ElasticsearchClient(transport);

            // 测试连接
            var info = this.client.info();
            log.info("Connected to Elasticsearch cluster: {}", info.clusterName());

            return this.client;

        } catch (Exception e) {
            throw new RuntimeException(
                    "elasticsearch.enable=true but connection failed. " +
                    "Either start Elasticsearch or set elasticsearch.enable=false to use MySQL LIKE fallback. " +
                    "Cause: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 ElasticsearchEmbeddingStore（LangChain4j 向量存储，可选附加写入）。
     * 整个类受 {@code @ConditionalOnProperty(elasticsearch.enable=true)} 保护，
     * 到达此方法时 {@code elasticsearchClient()} 必然已成功连接。
     */
    @Bean
    public ElasticsearchEmbeddingStore elasticsearchEmbeddingStore() {
        return ElasticsearchEmbeddingStore.builder()
                .serverUrl(esUris.split(",")[0])
                .indexName(indexName)
                .build();
    }

    /**
     * ES BM25 关键词检索器（ES 激活且连接成功时注册）。
     * 命名为 "esKeywordContentRetriever"，同时注册别名 "keywordContentRetriever"，
     * 使 {@code MysqlKeywordContentRetriever} 的 {@code @ConditionalOnMissingBean(name="esKeywordContentRetriever")}
     * 条件不成立，确保 MySQL 降级不被注册。
     */
    @Bean({"esKeywordContentRetriever", "keywordContentRetriever"})
    public ContentRetriever esKeywordContentRetriever() {
        return KeywordContentRetriever.builder()
                .elasticsearchUrl(esUris.split(",")[0])
                .indexName(indexName)
                .maxResults(100)
                .minScore(0.0)
                .build();
    }

    /**
     * 应用启动后初始化索引
     */
    @PostConstruct
    public void initIndex() {
        if (client == null) {
            return;
        }

        try {
            // 检查索引是否存在
            ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
            boolean exists = client.indices().exists(existsRequest).value();

            if (!exists) {
                log.info("Creating Elasticsearch index: {}", indexName);
                createIndex();
                log.info("Elasticsearch index created successfully");
            } else {
                log.info("Elasticsearch index already exists: {}", indexName);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize Elasticsearch index: {}. Keyword search may not work.", e.getMessage());
        }
    }

    /**
     * 创建索引（IK 分词 + 停用词）
     */
    private void createIndex() throws Exception {
        String settingsAndMappingJson = """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                    "analysis": {
                      "filter": {
                        "my_stop_filter": {
                          "type": "stop",
                          "stopwords": "_chinese_"
                        }
                      },
                      "analyzer": {
                        "ik_max": {
                          "type": "custom",
                          "tokenizer": "ik_max_word",
                          "filter": ["lowercase", "my_stop_filter"]
                        },
                        "ik_smart": {
                          "type": "custom",
                          "tokenizer": "ik_smart",
                          "filter": ["lowercase", "my_stop_filter"]
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "id": { "type": "keyword" },
                      "document_id": { "type": "keyword" },
                      "content": {
                        "type": "text",
                        "analyzer": "ik_max",
                        "search_analyzer": "ik_smart",
                        "fields": {
                          "smart": {
                            "type": "text",
                            "analyzer": "ik_smart",
                            "search_analyzer": "ik_smart"
                          }
                        }
                      },
                      "metadata": {
                        "type": "object",
                        "properties": {
                          "fileName": { "type": "keyword" },
                          "fileType": { "type": "keyword" },
                          "uploadUser": { "type": "keyword" }
                        }
                      }
                    }
                  }
                }
                """;

        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(indexName)
                .withJson(new StringReader(settingsAndMappingJson))
        );

        client.indices().create(request);
    }
}

