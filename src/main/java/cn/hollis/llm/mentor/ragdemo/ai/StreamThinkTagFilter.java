package cn.hollis.llm.mentor.ragdemo.ai;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式 Think-tag 过滤器。
 *
 * <p>在 SSE 流式输出中，{@code <think>...</think>} 标签可能跨多个 token 片段到达。
 * 此过滤器维护一个有状态缓冲区，确保：
 * <ul>
 *   <li>在 {@code <think>} 标签之前的内容正常透传。</li>
 *   <li>进入 {@code <think>} 后的所有内容被静默丢弃，直到遇到 {@code </think>}。</li>
 *   <li>{@code </think>} 之后的内容恢复正常透传。</li>
 *   <li>若流结束时 {@code <think>} 仍未闭合，缓冲区中所有内容被丢弃。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   Flux&lt;String&gt; clean = StreamThinkTagFilter.filter(rawFlux);
 * </pre>
 */
public final class StreamThinkTagFilter {

    private static final String THINK_OPEN  = "<think>";
    private static final String THINK_CLOSE = "</think>";

    private StreamThinkTagFilter() {}

    /**
     * 对流式 token Flux 应用 think-tag 过滤，返回干净的 Flux&lt;String&gt;。
     *
     * @param source 原始 token 流（每个元素为一个或多个字符的 token 片段）
     * @return 过滤后的 token 流（已剔除 think 块内容及标签）
     */
    public static Flux<String> filter(Flux<String> source) {
        // State shared across tokens within this stream session
        AtomicBoolean insideThink = new AtomicBoolean(false);
        AtomicReference<String> pending = new AtomicReference<>("");

        return source.flatMap(token -> {
            String combined = pending.get() + token;
            pending.set("");

            StringBuilder output = new StringBuilder();

            while (!combined.isEmpty()) {
                if (!insideThink.get()) {
                    // Looking for <think>
                    int openIdx = combined.toLowerCase().indexOf(THINK_OPEN);
                    if (openIdx == -1) {
                        // No <think> in this chunk — check if it's a partial match at the end
                        int partialLen = longestSuffixPrefixMatch(combined.toLowerCase(), THINK_OPEN);
                        if (partialLen > 0) {
                            // Possible partial <think> at end — hold it back
                            output.append(combined, 0, combined.length() - partialLen);
                            pending.set(combined.substring(combined.length() - partialLen));
                        } else {
                            output.append(combined);
                        }
                        break;
                    }
                    // Found opening tag — emit everything before it
                    output.append(combined, 0, openIdx);
                    combined = combined.substring(openIdx + THINK_OPEN.length());
                    insideThink.set(true);
                } else {
                    // Looking for </think>
                    int closeIdx = combined.toLowerCase().indexOf(THINK_CLOSE);
                    if (closeIdx == -1) {
                        // Still inside think block — check for partial </think> at end
                        int partialLen = longestSuffixPrefixMatch(combined.toLowerCase(), THINK_CLOSE);
                        if (partialLen > 0) {
                            pending.set(combined.substring(combined.length() - partialLen));
                        }
                        // Discard everything inside think block
                        break;
                    }
                    // Found closing tag — discard think content, continue after tag
                    combined = combined.substring(closeIdx + THINK_CLOSE.length());
                    insideThink.set(false);
                }
            }

            String out = output.toString();
            return out.isEmpty() ? Flux.empty() : Flux.just(out);
        });
    }

    /**
     * 计算 {@code text} 的最长后缀与 {@code pattern} 前缀的匹配长度。
     * 用于检测 token 边界处可能被截断的标签片段。
     */
    private static int longestSuffixPrefixMatch(String text, String pattern) {
        int maxLen = Math.min(text.length(), pattern.length() - 1);
        for (int len = maxLen; len > 0; len--) {
            String suffix = text.substring(text.length() - len);
            String prefix = pattern.substring(0, len);
            if (suffix.equals(prefix)) return len;
        }
        return 0;
    }
}
