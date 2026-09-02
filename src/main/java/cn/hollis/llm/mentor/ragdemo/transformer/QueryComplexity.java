package cn.hollis.llm.mentor.ragdemo.transformer;

/**
 * 查询复杂度分级
 *
 * <p>由 {@link QueryComplexityClassifier} 规则判定，用于查询改写管道的 L1 短路。
 */
public enum QueryComplexity {

    /**
     * 简单查询 — 无指代词、意图清晰、实体明确。
     * <p>短路跳过改写管道，直接使用原始查询检索，省去 3+ 次 LLM 调用。
     */
    SIMPLE,

    /**
     * 复杂查询 — 含指代词/多子问题/模糊追问/分析类关键词。
     * <p>需走完整改写管道（enrich → stepBack → decompose → diversify）。
     */
    COMPLEX
}
