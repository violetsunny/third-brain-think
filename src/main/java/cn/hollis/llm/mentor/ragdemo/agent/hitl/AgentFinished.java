package cn.hollis.llm.mentor.ragdemo.agent.hitl;

/** Agent 正常完成，携带最终回答文本。 */
public record AgentFinished(String content) implements AgentResult {
}
