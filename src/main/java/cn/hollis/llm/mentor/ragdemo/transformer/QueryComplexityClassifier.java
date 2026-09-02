package cn.hollis.llm.mentor.ragdemo.transformer;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 查询复杂度分类器（L1 规则短路）
 *
 * <p>借鉴 gogo-agent 意图识别三层短路的设计，在查询改写管道入口做规则判定：
 * <ul>
 *   <li>{@link QueryComplexity#SIMPLE} — 无指代词、意图清晰、实体明确，直接跳过改写</li>
 *   <li>{@link QueryComplexity#COMPLEX} — 含指代词/多子问题/模糊追问，需走完整改写管道</li>
 * </ul>
 *
 * <p>确定性逻辑，不调用 LLM，延迟 &lt; 1ms。
 */
@Slf4j
public class QueryComplexityClassifier {

    /** 需要上下文消解的指代词 */
    private static final Set<String> CONTEXT_PRONOUNS = Set.of(
            "它", "它们", "这个", "那个", "这些", "那些", "其", "该", "此",
            "他", "她", "他们", "她们", "之前", "刚才", "上面"
    );

    /** 模糊追问词 — 单独出现或句首出现时表示上下文依赖 */
    private static final Set<String> FOLLOW_UP_INDICATORS = Set.of(
            "继续", "然后呢", "还有呢", "接着", "另外", "对了", "还有"
    );

    /** 多子问题分隔符模式 — 逗号/分号后接疑问词 */
    private static final Pattern MULTI_QUESTION_PATTERN = Pattern.compile(
            "[，,；;].*(?:吗|呢|怎么|如何|什么|哪些|多少|是不是|能不能|可以吗)"
    );

    /** 分析/对比类关键词 — 通常需要多路检索 */
    private static final Set<String> ANALYSIS_KEYWORDS = Set.of(
            "对比", "比较", "区别", "分析", "异同", "优缺点", "利弊"
    );

    /** 最小有意义查询长度（中文字符） */
    private static final int MIN_MEANINGFUL_LENGTH = 3;

    /** 最大简单查询长度 — 超过则可能包含多个意图 */
    private static final int MAX_SIMPLE_LENGTH = 80;

    /**
     * 分类查询复杂度。
     *
     * @param query 用户原始查询
     * @return {@link QueryComplexity#SIMPLE} 或 {@link QueryComplexity#COMPLEX}
     */
    public QueryComplexity classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryComplexity.COMPLEX;
        }

        String trimmed = query.trim();

        if (trimmed.length() < MIN_MEANINGFUL_LENGTH) {
            log.debug("查询过短({}字符)，判定为 COMPLEX: {}", trimmed.length(), trimmed);
            return QueryComplexity.COMPLEX;
        }

        if (trimmed.length() > MAX_SIMPLE_LENGTH) {
            log.debug("查询过长({}字符)，判定为 COMPLEX", trimmed.length());
            return QueryComplexity.COMPLEX;
        }

        for (String pronoun : CONTEXT_PRONOUNS) {
            if (trimmed.contains(pronoun)) {
                log.debug("检测到指代词「{}」，判定为 COMPLEX: {}", pronoun, trimmed);
                return QueryComplexity.COMPLEX;
            }
        }

        for (String followUp : FOLLOW_UP_INDICATORS) {
            if (trimmed.startsWith(followUp) || trimmed.equals(followUp)) {
                log.debug("检测到模糊追问词「{}」，判定为 COMPLEX: {}", followUp, trimmed);
                return QueryComplexity.COMPLEX;
            }
        }

        if (MULTI_QUESTION_PATTERN.matcher(trimmed).find()) {
            log.debug("检测到多子问题模式，判定为 COMPLEX: {}", trimmed);
            return QueryComplexity.COMPLEX;
        }

        for (String keyword : ANALYSIS_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                log.debug("检测到分析关键词「{}」，判定为 COMPLEX: {}", keyword, trimmed);
                return QueryComplexity.COMPLEX;
            }
        }

        log.debug("查询判定为 SIMPLE（无指代/无追问/单意图）: {}", trimmed);
        return QueryComplexity.SIMPLE;
    }
}
