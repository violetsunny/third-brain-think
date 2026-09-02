package cn.hollis.llm.mentor.ragdemo.agent.hitl;

/**
 * 待人工审批的工具调用。
 *
 * <p>{@link #approve()} 生成 APPROVED 反馈，{@link #reject(String)} 生成 REJECTED 反馈。
 */
public record PendingToolCall(
        String id,
        String name,
        String arguments,
        FeedbackResult result,
        String description
) {

    public enum FeedbackResult {
        APPROVED,
        REJECTED,
        EDIT
    }

    public PendingToolCall approve() {
        return new PendingToolCall(id, name, arguments, FeedbackResult.APPROVED, description);
    }

    public PendingToolCall reject(String reason) {
        return new PendingToolCall(id, name, arguments, FeedbackResult.REJECTED, reason);
    }
}
