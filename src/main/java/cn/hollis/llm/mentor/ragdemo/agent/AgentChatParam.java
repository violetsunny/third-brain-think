package cn.hollis.llm.mentor.ragdemo.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 聊天请求参数
 */
@Data
public class AgentChatParam {

    /**
     * 会话ID（用于多轮对话记忆，可为空则开启新会话）
     */
    private String conversationId;

    /**
     * 用户问题
     */
    @NotBlank(message = "question 不能为空")
    private String question;
}
