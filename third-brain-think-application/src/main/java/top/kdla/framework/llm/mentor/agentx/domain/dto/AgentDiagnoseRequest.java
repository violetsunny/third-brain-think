package top.kdla.framework.llm.mentor.agentx.domain.dto;

/**
 * Agent 评测问题诊断请求。
 *
 * @param conversationId 被测执行的会话窗口 ID（评测结果文件里存的即此值，诊断入口）
 * @param question       用户原始问题（可空，agentx_conversation 里有备份）
 * @param goldAnswer     标准答案（出题时预执行固化）
 * @param agentAnswer    Agent 实际回答（被裁判判不通过的那份）
 * @param judgeReason    裁判判语（可选，提示错误在哪）
 */
public record AgentDiagnoseRequest(
        String conversationId,
        String question,
        String goldAnswer,
        String agentAnswer,
        String judgeReason
) {
}
