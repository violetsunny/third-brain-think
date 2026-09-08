package top.kdla.framework.llm.mentor.agentx.domain.dto;

/**
 * Agent 评测单次判分响应。
 *
 * @param success      裁判是否成功执行
 * @param pass         是否通过：正确性 ≥ 3（核心数据错、核心结论错、明显编造即不通过）
 * @param correctness  正确性档位分 0-5
 * @param completeness 完整性档位分 0-5
 * @param relevance    相关性档位分 0-5
 * @param logic        逻辑性档位分 0-5
 * @param totalScore   加权总分 = 正确性×0.4 + 完整性×0.2 + 相关性×0.2 + 逻辑性×0.2（程序计算）
 * @param reason       裁判理由
 * @param error        失败原因
 * @param durationMs   裁判耗时
 */
public record AgentEvalJudgeResponse(
        boolean success,
        boolean pass,
        int correctness,
        int completeness,
        int relevance,
        int logic,
        double totalScore,
        String reason,
        String error,
        long durationMs
) {
}
