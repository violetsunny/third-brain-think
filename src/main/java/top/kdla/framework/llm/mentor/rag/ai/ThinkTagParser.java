package top.kdla.framework.llm.mentor.rag.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Think-tag 内容过滤工具。
 *
 * <p>部分推理模型（如 DeepSeek-R1）会在回答中输出 {@code <think>...</think>} 标签包裹的推理过程。
 * 此工具类提供静态方法剥离这些内部思考内容，只保留最终回答文本。
 */
public final class ThinkTagParser {

    private static final Pattern THINK_PATTERN =
            Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

    private ThinkTagParser() {
    }

    /**
     * 移除所有 {@code <think>...</think>} 块，返回清洁的回答文本。
     *
     * @param raw 原始 LLM 输出（可能含 think 标签）
     * @return 过滤后的文本，首尾空白已 trim
     */
    public static String strip(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Matcher m = THINK_PATTERN.matcher(raw);
        return m.replaceAll("").trim();
    }

    /**
     * 提取第一个 {@code <think>...</think>} 块的内容（不含标签）。
     *
     * @param raw 原始 LLM 输出
     * @return think 块内容，若不存在则返回空字符串
     */
    public static String extractThink(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Pattern inner = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE);
        Matcher m = inner.matcher(raw);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 检查文本是否包含 think 标签。
     */
    public static boolean hasThinkTag(String raw) {
        if (raw == null) return false;
        return THINK_PATTERN.matcher(raw).find();
    }
}
