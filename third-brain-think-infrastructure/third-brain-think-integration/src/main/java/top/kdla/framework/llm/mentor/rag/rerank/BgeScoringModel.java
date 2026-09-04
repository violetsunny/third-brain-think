package top.kdla.framework.llm.mentor.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BGE 重排序模型（支持 BGE Reranker HTTP API，含 mock 降级）。
 *
 * <p>当 {@code bge.reranker.enabled=true} 时，调用兼容 FlagEmbedding / Jina Reranker
 * 接口规范的 HTTP 服务：
 * <pre>
 *   POST {bge.reranker.url}/rerank
 *   Body: {"query": "...", "passages": ["...", ...]}
 *   Response: {"scores": [0.92, 0.85, ...]}
 * </pre>
 *
 * <p>若 {@code bge.reranker.enabled=false} 或 HTTP 调用失败，自动降级为基于词频匹配的
 * mock 打分，保证流程不中断。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 *   bge:
 *     reranker:
 *       enabled: true
 *       url: http://localhost:8001
 *       timeout-ms: 5000
 * </pre>
 */
@Slf4j
@Component
public class BgeScoringModel {

    @Value("${bge.reranker.enabled:false}")
    private boolean enabled;

    @Value("${bge.reranker.url:http://localhost:8001}")
    private String rerankerUrl;

    @Value("${bge.reranker.timeout-ms:5000}")
    private int timeoutMs;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 对候选文档列表按与查询的相关性重排，返回得分最高的 {@code topK} 篇。
     *
     * @param query     用户查询文本
     * @param documents 候选文档列表
     * @param topK      返回文档数量上限
     * @return 重排后的文档列表（已降序）
     */
    public List<Document> rerank(String query, List<Document> documents, int topK) {
        if (documents == null || documents.isEmpty()) return List.of();
        if (query == null || query.isBlank()) return documents.stream().limit(topK).collect(Collectors.toList());

        log.debug("BgeScoringModel.rerank: query='{}' candidates={} topK={} enabled={}",
                shorten(query), documents.size(), topK, enabled);

        List<Double> scores = enabled
                ? callBgeRerankerApi(query, documents)
                : mockScores(query, documents);

        // Pair scores with documents and sort descending
        List<Map.Entry<Document, Double>> paired = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            double score = i < scores.size() ? scores.get(i) : 0.0;
            documents.get(i).getMetadata().put("rerank_score", score);
            paired.add(Map.entry(documents.get(i), score));
        }

        return paired.stream()
                .sorted(Map.Entry.<Document, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** Overload without topK — returns all documents re-ranked. */
    public List<Document> rerank(String query, List<Document> documents) {
        return rerank(query, documents, documents == null ? 0 : documents.size());
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP call to BGE Reranker service
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Double> callBgeRerankerApi(String query, List<Document> documents) {
        try {
            List<String> passages = documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("passages", passages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String url = rerankerUrl.endsWith("/") ? rerankerUrl + "rerank" : rerankerUrl + "/rerank";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object scoresObj = response.getBody().get("scores");
                if (scoresObj instanceof List<?> rawList) {
                    List<Double> result = new ArrayList<>();
                    for (Object s : rawList) {
                        result.add(s instanceof Number n ? n.doubleValue() : 0.0);
                    }
                    log.debug("BgeScoringModel: API returned {} scores", result.size());
                    return result;
                }
            }
            log.warn("BgeScoringModel: unexpected API response, falling back to mock scoring");
        } catch (Exception e) {
            log.warn("BgeScoringModel: API call failed ({}), falling back to mock scoring", e.getMessage());
        }
        return mockScores(query, documents);
    }

    // ─────────────────────────────────────────────────────────────
    // Mock fallback scoring (token overlap)
    // ─────────────────────────────────────────────────────────────

    private List<Double> mockScores(String query, List<Document> documents) {
        List<String> queryTokens = tokenize(query);
        return documents.stream()
                .map(doc -> scoreMock(queryTokens, doc.getText()))
                .collect(Collectors.toList());
    }

    private double scoreMock(List<String> queryTokens, String docText) {
        if (docText == null || docText.isBlank() || queryTokens.isEmpty()) return 0.0;
        String lower = docText.toLowerCase();
        long matched = queryTokens.stream().filter(t -> lower.contains(t.toLowerCase())).count();
        return (double) matched / queryTokens.size();
    }

    private List<String> tokenize(String text) {
        return List.of(text.toLowerCase().split("[\\s\\p{Punct}]+"));
    }

    private String shorten(String q) {
        return q.length() > 30 ? q.substring(0, 30) + "..." : q;
    }
}
