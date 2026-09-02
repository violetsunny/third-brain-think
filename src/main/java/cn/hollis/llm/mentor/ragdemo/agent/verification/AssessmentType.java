package cn.hollis.llm.mentor.ragdemo.agent.verification;

/**
 * 主观评估维度
 */
public enum AssessmentType {
    /** 回答是否基于检索到的源文档内容（非幻觉） */
    GROUNDEDNESS,
    /** 回答是否与用户问题相关 */
    RELEVANCE,
    /** 回答是否完整覆盖了问题的各个方面 */
    COMPLETENESS
}
