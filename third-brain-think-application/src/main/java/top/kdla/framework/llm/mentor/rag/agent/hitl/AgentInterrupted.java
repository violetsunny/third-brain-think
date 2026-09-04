package top.kdla.framework.llm.mentor.rag.agent.hitl;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * Agent 因 HITL 中断，等待人工审批。
 *
 * <p>调用方持有此对象，完成人工审批后通过 {@code HITLReactAgent.resume(interrupted, feedbacks)} 继续执行。
 */
public record AgentInterrupted(
        List<PendingToolCall> pendingToolCalls,
        List<Message> checkpointMessages,
        Map<String, Object> context
) implements AgentResult {
}
