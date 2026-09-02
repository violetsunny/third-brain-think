package top.kdla.framework.llm.mentor.rag.rerank;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序内容聚合器
 * 支持DashScope Qwen-rerank、BGE-Reranker等重排序模型
 */
@Slf4j
public class ReRankingContentAggregator implements ContentAggregator {

    private final String apiKey;
    private final String provider;
    private final String model;
    private final Integer maxResults;
    private final Double minScore;

    @Builder
    public ReRankingContentAggregator(String apiKey,
                                      String provider,
                                      String model,
                                      Integer maxResults,
                                      Double minScore) {
        this.apiKey = apiKey;
        this.provider = provider != null ? provider : "dashscope";
        this.model = model != null ? model : "gte-rerank-v2";
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        log.debug("执行重排序，查询数量: {}", queryToContents.size());

        if (queryToContents.isEmpty()) {
            return Collections.emptyList();
        }

        // 获取第一个查询（通常只有一个）
        Query query = queryToContents.keySet().iterator().next();
        Collection<List<Content>> contentsList = queryToContents.get(query);

        // 合并所有检索列表
        List<Content> allContents = new ArrayList<>();
        for (List<Content> contents : contentsList) {
            allContents.addAll(contents);
        }

        if (allContents.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 提取文档文本
            List<String> documents = allContents.stream()
                    .map(content -> content.textSegment().text())
                    .collect(Collectors.toList());

            // 调用重排序API
            List<Double> scores = callRerankAPI(query.text(), documents);

            // 将分数与内容关联
            List<ContentWithScore> contentWithScores = new ArrayList<>();
            for (int i = 0; i < allContents.size() && i < scores.size(); i++) {
                double score = scores.get(i);
                
                // 应用最小分数过滤
                if (minScore == null || score >= minScore) {
                    contentWithScores.add(new ContentWithScore(allContents.get(i), score));
                }
            }

            // 按分数降序排序
            List<Content> rerankedContents = contentWithScores.stream()
                    .sorted(Comparator.comparingDouble(ContentWithScore::getScore).reversed())
                    .map(ContentWithScore::getContent)
                    .collect(Collectors.toList());

            // 限制返回数量
            if (maxResults != null && maxResults > 0) {
                rerankedContents = rerankedContents.stream()
                        .limit(maxResults)
                        .collect(Collectors.toList());
            }

            log.debug("重排序完成，返回{}条结果", rerankedContents.size());
            return rerankedContents;

        } catch (Exception e) {
            log.error("重排序失败，返回原始结果", e);
            return allContents;
        }
    }

    /**
     * 调用重排序API
     */
    private List<Double> callRerankAPI(String query, List<String> documents) throws Exception {
        switch (provider.toLowerCase()) {
            case "dashscope":
                return callDashScopeRerank(query, documents);
            case "bge":
                return callBGEReranker(query, documents);
            default:
                log.warn("未知的重排序提供商: {}, 返回默认分数", provider);
                return documents.stream().map(d -> 0.5).collect(Collectors.toList());
        }
    }

    /**
     * 调用DashScope Qwen-rerank API
     */
    private List<Double> callDashScopeRerank(String query, List<String> documents) {
        log.debug("调用DashScope重排序API");

        String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", documents.size());
        requestBody.put("parameters", parameters);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序API调用失败: " + response.getStatusCode());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("output")) {
            throw new RuntimeException("API响应格式异常");
        }

        Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
        List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) output.get("results");

        // 按原始顺序返回分数
        Map<Integer, Double> indexToScore = new HashMap<>();
        for (Map<String, Object> item : rerankedResults) {
            Integer index = (Integer) item.get("index");
            Double score = ((Number) item.get("relevance_score")).doubleValue();
            indexToScore.put(index, score);
        }

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            scores.add(indexToScore.getOrDefault(i, 0.0));
        }

        return scores;
    }

    /**
     * 调用BGE-Reranker (本地 ONNX 模型)
     *
     * TODO: 实现 BGE-Reranker 本地调用
     * 需要添加依赖: langchain4j-community-rerank-bge 或专门的 rerank 模型
     * 目前 BGE 主要用作 Embedding，Rerank 需要使用专门的 Rerank 模型
     *
     * 方案1: 使用 DashScope API (已实现)
     * 方案2: 使用本地部署的 BGE-Reranker 模型 (需要额外依赖和模型文件)
     *   - 需要下载 BGE-reranker-base 或 BGE-reranker-large 模型
     *   - 使用 ONNX Runtime 或 HuggingFace Transformers 加载模型
     *   - 计算 query-document 的相关性分数
     * 方案3: 使用 HuggingFace Inference API
     */
    private List<Double> callBGEReranker(String query, List<String> documents) {
        log.warn("BGE-Reranker 本地模型尚未配置，返回默认分数。建议配置 rag.rerank.provider=dashscope");
        return documents.stream().map(d -> 0.5).collect(Collectors.toList());
    }

    /**
     * 内容与分数配对
     */
    private static class ContentWithScore {
        private final Content content;
        private final double score;

        public ContentWithScore(Content content, double score) {
            this.content = content;
            this.score = score;
        }

        public Content getContent() {
            return content;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * 创建构建器
     */
    public static ReRankingContentAggregatorBuilder builder() {
        return new ReRankingContentAggregatorBuilder();
    }
}
