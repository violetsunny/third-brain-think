package top.kdla.framework.llm.mentor.rag.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.copyIfNotNull;
import static dev.langchain4j.internal.ValidationUtils.*;

/**
 * 关键词检索器 - 基于Elasticsearch BM25算法
 * 使用关键词匹配进行检索
 */
@Slf4j
public class KeywordContentRetriever implements ContentRetriever {

    private final String elasticsearchUrl;
    private final String indexName;
    private final Integer maxResults;
    private final Double minScore;
    private final RestTemplate restTemplate;

    @Builder
    public KeywordContentRetriever(String elasticsearchUrl,
                                   String indexName,
                                   Integer maxResults,
                                   Double minScore) {
        this.elasticsearchUrl = ensureNotBlank(elasticsearchUrl, "elasticsearchUrl");
        this.indexName = ensureNotBlank(indexName, "indexName");
        this.maxResults = ensureGreaterThanZero(maxResults, "maxResults");
        this.minScore = minScore != null ? minScore : 0.0;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public List<Content> retrieve(Query query) {
        log.debug("执行关键词检索: query={}, maxResults={}", query.text(), maxResults);

        try {
            // 构建Elasticsearch查询
            Map<String, Object> searchRequest = buildSearchRequest(query.text());

            // 发送请求到Elasticsearch
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(searchRequest, headers);

            String url = elasticsearchUrl + "/" + indexName + "/_search";
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            // 解析响应
            List<Content> contents = parseResponse(response.getBody());
            log.debug("关键词检索返回 {} 个结果", contents.size());
            return contents;

        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建Elasticsearch搜索请求（使用multi_match查询）
     */
    private Map<String, Object> buildSearchRequest(String queryText) {
        Map<String, Object> request = new HashMap<>();

        // 构建query部分
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> multiMatch = new HashMap<>();
        multiMatch.put("query", queryText);
        multiMatch.put("fields", Arrays.asList("text^1", "title^2", "metadata.*"));
        multiMatch.put("type", "best_fields");
        multiMatch.put("fuzziness", "AUTO");
        
        query.put("multi_match", multiMatch);
        request.put("query", query);

        // 设置size
        request.put("size", maxResults);

        // 添加minimum_score过滤
        if (minScore > 0) {
            request.put("min_score", minScore);
        }

        return request;
    }

    /**
     * 解析Elasticsearch响应
     */
    @SuppressWarnings("unchecked")
    private List<Content> parseResponse(Map<String, Object> responseBody) {
        if (responseBody == null || !responseBody.containsKey("hits")) {
            return Collections.emptyList();
        }

        Map<String, Object> hits = (Map<String, Object>) responseBody.get("hits");
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");

        if (hitList == null || hitList.isEmpty()) {
            return Collections.emptyList();
        }

        return hitList.stream()
                .map(hit -> {
                    Map<String, Object> source = (Map<String, Object>) hit.get("_source");
                    String text = (String) source.get("text");
                    
                    if (text != null && !text.isEmpty()) {
                        TextSegment segment = TextSegment.from(text);
                        return Content.from(segment);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "KeywordContentRetriever{" +
                "elasticsearchUrl='" + elasticsearchUrl + '\'' +
                ", indexName='" + indexName + '\'' +
                ", maxResults=" + maxResults +
                ", minScore=" + minScore +
                '}';
    }
}
