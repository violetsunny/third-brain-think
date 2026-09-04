package top.kdla.framework.llm.mentor.rag.agent.hitl;

/** Agent 正常完成，携带最终回答文本。 */
public record AgentFinished(String content) implements AgentResult {
}
