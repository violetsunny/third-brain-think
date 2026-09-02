package cn.hollis.llm.mentor.ragdemo.agent.hitl;

/**
 * Agent 执行结果接口。
 *
 * <p>两个实现：
 * <ul>
 *   <li>{@link AgentFinished} — 执行完成，携带最终文本</li>
 *   <li>{@link AgentInterrupted} — 等待人工审批，携带中断快照</li>
 * </ul>
 */
public sealed interface AgentResult permits AgentFinished, AgentInterrupted {
}
