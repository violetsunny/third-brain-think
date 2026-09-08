package top.kdla.framework.llm.mentor.agentx.domain.dto;

/**
 * Agent 评测单次判分请求。
 *
 * @param question    用户原始问题
 * @param goldAnswer  标准答案（出题时预执行固化）
 * @param agentAnswer Agent 交付报告（被评测对象）
 */
public record AgentEvalJudgeRequest(
        String question,
        String goldAnswer,
        String agentAnswer
) {
}
