package cn.hollis.llm.mentor.ragdemo.agent.hitl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL 会话状态。
 *
 * <p>记录已处理的 tool call ID（避免重复执行）和已审批的工具名称（本会话内只需审批一次）。
 */
public class HITLState {

    private final Set<String> consumedToolCallIds = ConcurrentHashMap.newKeySet();
    private final Set<String> approvedToolNames = ConcurrentHashMap.newKeySet();

    public HITLState() {
    }

    public boolean isConsumed(String toolCallId) {
        return consumedToolCallIds.contains(toolCallId);
    }

    public void markConsumed(String toolCallId) {
        consumedToolCallIds.add(toolCallId);
    }

    /** 判断该工具名称是否已被人工审批过（一次会话内只需审批一次）。 */
    public boolean isToolNameApproved(String toolName) {
        return approvedToolNames.contains(toolName);
    }

    /** 标记该工具名称已通过人工审批。 */
    public void markToolNameApproved(String toolName) {
        approvedToolNames.add(toolName);
    }
}
