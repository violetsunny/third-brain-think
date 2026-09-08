package top.kdla.framework.llm.mentor.agentx.domain.dto;

/**
 * Agent 评测问题诊断响应。
 *
 * @param success       诊断是否成功执行
 * @param conversationId 被测会话窗口 ID（回显）
 * @param rootSessionId 根源错误所在 session（无法确定为 null）
 * @param rootRound     根源错误所在轮次，第一次出错的那一轮（无法定位为 null）
 * @param attribution   归因类别：模型能力 / 提示词 / 工具 / 框架
 * @param evidence      trace 证据（引用轮次与原文片段）
 * @param analysis      诊断分析（错误如何发生、如何级联）
 * @param suggestion    修改建议
 * @param error         失败原因
 * @param durationMs    诊断耗时
 */
public record AgentDiagnoseResponse(
        boolean success,
        String conversationId,
        String rootSessionId,
        Integer rootRound,
        String attribution,
        String evidence,
        String analysis,
        String suggestion,
        String error,
        long durationMs
) {
}
