package top.kdla.framework.llm.mentor.agentx.domain.dto;

/**
 * BIRD 评测单题响应。
 */
public record BirdEvalResponse(
        boolean success,
        String sql,
        String error,
        long durationMs
) {
}
