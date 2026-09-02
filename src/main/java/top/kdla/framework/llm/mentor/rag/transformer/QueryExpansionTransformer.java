package top.kdla.framework.llm.mentor.rag.transformer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询扩展 Transformer
 * 调用 LLM 将用户查询扩展为多个语义相关的变体，提升召回率
 *
 * 通过 rag.query-expansion.enabled=true 激活
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.query-expansion.enabled", havingValue = "true")
public class QueryExpansionTransformer implements QueryTransformer {

    private final ChatModel chatModel;
    private final int expansionCount;

    public QueryExpansionTransformer(ChatModel chatModel,
                                     @Value("${rag.query-expansion.count:3}") int expansionCount) {
        this.chatModel = chatModel;
        this.expansionCount = expansionCount;
    }

    @Override
    public Collection<Query> transform(Query query) {
        String originalText = query.text();
        log.debug("查询扩展开始: original={}", originalText);

        try {
            String prompt = buildPrompt(originalText, expansionCount);
            String response = chatModel.chat(prompt);
            List<String> expanded = parseJsonArray(response);

            if (expanded.isEmpty()) {
                log.warn("查询扩展解析结果为空，降级返回原始查询: query={}", originalText);
                return Collections.singletonList(query);
            }

            // 原始查询 + 扩展查询
            List<Query> result = expanded.stream()
                    .map(text -> Query.from(text, query.metadata()))
                    .collect(Collectors.toList());
            result.add(0, query);  // 原始查询放首位

            log.debug("查询扩展完成: original={}, expanded={}", originalText, expanded);
            return result;

        } catch (Exception e) {
            log.warn("查询扩展失败，降级返回原始查询: query={}, error={}", originalText, e.getMessage());
            return Collections.singletonList(query);
        }
    }

    private String buildPrompt(String query, int count) {
        return String.format(
                "请将以下用户查询扩展为 %d 个语义相关的变体查询，用于提升文档检索召回率。" +
                "只返回 JSON 数组格式，例如: [\"变体1\", \"变体2\", \"变体3\"]，不要包含其他内容。\n\n" +
                "原始查询: %s",
                count, query);
    }

    private List<String> parseJsonArray(String response) {
        try {
            // 从 response 中提取 JSON 数组部分
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start == -1 || end == -1 || end <= start) {
                return Collections.emptyList();
            }
            String jsonPart = response.substring(start, end + 1);
            return JSON.parseObject(jsonPart, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("JSON 解析失败: response={}, error={}", response, e.getMessage());
            return Collections.emptyList();
        }
    }
}
