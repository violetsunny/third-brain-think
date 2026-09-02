package cn.hollis.llm.mentor.ragdemo.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 搜索工具（支持 Tavily Search API，含 mock 降级）。
 *
 * <p>当 {@code tavily.api-key} 非空时，调用 Tavily Search API 获取真实搜索结果；
 * 否则返回模拟结果，便于端到端流程验证。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 *   tavily:
 *     api-key: ${TAVILY_API_KEY:}
 *     search-depth: basic   # basic | advanced
 *     max-results: 5
 * </pre>
 *
 * <p>Tavily API 文档：https://docs.tavily.com/docs/tavily-api/rest_api
 */
@Slf4j
@Component
public class WebSearchTool {

    private static final String TAVILY_URL = "https://api.tavily.com/search";

    @Value("${tavily.api-key:}")
    private String apiKey;

    @Value("${tavily.search-depth:basic}")
    private String searchDepth;

    @Value("${tavily.max-results:5}")
    private int maxResults;

    private final RestTemplate restTemplate = new RestTemplate();

    @Tool(description = "搜索互联网获取最新信息。输入搜索关键词，返回相关搜索结果摘要。适合查询实时新闻、当前事件、最新数据等知识库中没有的信息。")
    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "[搜索工具] 查询为空，请提供有效的搜索关键词。";
        }

        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return callTavilyApi(query);
            } catch (Exception e) {
                log.warn("WebSearchTool: Tavily API call failed ({}), falling back to mock", e.getMessage());
            }
        } else {
            log.debug("WebSearchTool: tavily.api-key not configured, returning mock results");
        }

        return mockResult(query);
    }

    // ─────────────────────────────────────────────────────────────
    // Tavily Search API
    // ─────────────────────────────────────────────────────────────

    private String callTavilyApi(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("search_depth", searchDepth);
        body.put("max_results", maxResults);
        body.put("include_answer", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(TAVILY_URL, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Tavily API returned status: " + response.getStatusCode());
        }

        return formatTavilyResponse(query, response.getBody());
    }

    private String formatTavilyResponse(String query, String json) {
        try {
            JSONObject obj = JSON.parseObject(json);

            StringBuilder sb = new StringBuilder();
            sb.append("[搜索结果] 关键词：").append(query).append("\n\n");

            // 直接回答（若有）
            String answer = obj.getString("answer");
            if (answer != null && !answer.isBlank()) {
                sb.append("综合摘要：").append(answer).append("\n\n");
            }

            // 逐条结果
            JSONArray results = obj.getJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.size(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    sb.append(i + 1).append(". ");
                    sb.append(r.getString("title")).append("\n");
                    sb.append("   链接: ").append(r.getString("url")).append("\n");
                    String content = r.getString("content");
                    if (content != null && !content.isBlank()) {
                        sb.append("   摘要: ").append(content.length() > 300
                                ? content.substring(0, 300) + "..."
                                : content).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("WebSearchTool: failed to parse Tavily response", e);
            return "[搜索结果] " + json;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Mock fallback
    // ─────────────────────────────────────────────────────────────

    private String mockResult(String query) {
        return """
                [搜索结果] 关键词：%s
                
                1. 示例结果一：这是关于 "%s" 的第一条搜索结果摘要。
                2. 示例结果二：这是关于 "%s" 的第二条搜索结果摘要，包含更多详细信息。
                3. 示例结果三：这是关于 "%s" 的第三条搜索结果摘要。
                
                注意：当前为 mock 实现，配置 tavily.api-key 后将返回真实搜索结果。
                """.formatted(query, query, query, query);
    }
}
