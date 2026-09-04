package top.kdla.framework.llm.mentor.rag.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文档清洗器。
 *
 * <p>在文档进入向量化流程之前，对 {@link Document} 列表执行以下清洗步骤：
 * <ol>
 *   <li>过滤空文档（content 为 null 或纯空白）</li>
 *   <li>压缩多余空白（连续换行/空格合并）</li>
 *   <li>去除零宽字符、控制字符等噪音</li>
 *   <li>截断超长文档（超过 {@code maxChars} 时截断并标记元数据）</li>
 * </ol>
 */
@Slf4j
@Component
public class DocumentCleaner {

    /** 单文档最大字符数（超过则截断）。 */
    private static final int DEFAULT_MAX_CHARS = 8000;

    /**
     * 使用默认配置清洗文档列表。
     */
    public List<Document> clean(List<Document> documents) {
        return clean(documents, DEFAULT_MAX_CHARS);
    }

    /**
     * 使用指定最大字符数清洗文档列表。
     */
    public List<Document> clean(List<Document> documents, int maxChars) {
        if (documents == null || documents.isEmpty()) return List.of();

        List<Document> result = documents.stream()
                .filter(Objects::nonNull)
                .filter(d -> StringUtils.hasText(d.getText()))
                .map(d -> cleanDocument(d, maxChars))
                .filter(d -> StringUtils.hasText(d.getText()))
                .collect(Collectors.toList());

        log.info("DocumentCleaner: input={} output={}", documents.size(), result.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private Document cleanDocument(Document doc, int maxChars) {
        String cleaned = cleanText(doc.getText(), maxChars);

        if (cleaned.length() < doc.getText().length()) {
            doc.getMetadata().put("truncated", true);
            doc.getMetadata().put("original_length", doc.getText().length());
        }

        return new Document(cleaned, doc.getMetadata());
    }

    /**
     * 对原始文本字符串执行清洗流水线（不依赖 Spring AI Document 类型）。
     * 可被 LangChain4j Document 流程直接调用。
     *
     * @param text     原始文本
     * @return 清洗后的文本
     */
    public String cleanText(String text) {
        return cleanText(text, DEFAULT_MAX_CHARS);
    }

    /**
     * 对原始文本字符串执行清洗流水线。
     *
     * @param text     原始文本
     * @param maxChars 截断阈值
     * @return 清洗后的文本（长度不超过 maxChars）
     */
    public String cleanText(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";

        // 1. 去除零宽字符和控制字符（保留 \n \t \r）
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\uFEFF\u200B-\u200D\u2060\uFFFD]", "");

        // 2. 压缩连续空白行（3+ 换行 → 2 换行）
        text = text.replaceAll("\n{3,}", "\n\n");

        // 3. 压缩行内连续空格（2+ 空格 → 1 空格）
        text = text.replaceAll("[ \\t]{2,}", " ");

        // 4. trim
        text = text.trim();

        // 5. 截断超长文本
        if (text.length() > maxChars) {
            log.warn("DocumentCleaner.cleanText: truncating from {} to {} chars", text.length(), maxChars);
            text = text.substring(0, maxChars);
        }

        return text;
    }
}
