package cn.hollis.llm.mentor.ragdemo.agent;

import lombok.Data;

/**
 * Agent 聊天响应结果
 */
@Data
public class AgentChatResult {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * Agent 回答内容
     */
    private String answer;

    public static AgentChatResult of(String conversationId, String answer) {
        AgentChatResult result = new AgentChatResult();
        result.setConversationId(conversationId);
        result.setAnswer(answer);
        return result;
    }
}
