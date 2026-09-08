package top.kdla.framework.llm.mentor.agentx.domain.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_conversation 表实体。
 *
 * <p>v1.0.1 新增：每次 Agent 调用一行，记录调用边界与执行状态。
 */
@Data
@TableName("agentx_conversation")
public class AgentxConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private String userId;

    private String question;

    private String status;

    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
