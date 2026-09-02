package cn.hollis.llm.mentor.ragdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI对话会话实体类
 */
@Data
@TableName("chat_conversation")
public class ChatConversation {
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 会话唯一标识(UUID)
     */
    @TableField("conversation_id")
    private String conversationId;
    
    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;
    
    /**
     * 会话标题
     */
    @TableField("title")
    private String title;
    
    /**
     * 会话状态: ACTIVE-活跃, ARCHIVED-已归档, DELETED-已删除
     */
    @TableField("status")
    private String status;
    
    /**
     * 消息数量
     */
    @TableField("message_count")
    private Integer messageCount;
    
    /**
     * 最后一条消息时间
     */
    @TableField("last_message_time")
    private LocalDateTime lastMessageTime;
    
    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
