package cn.hollis.llm.mentor.ragdemo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI对话消息实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message")
public class ChatMessage {
    
    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /** 消息唯一标识(UUID) */
    @TableField("message_id")
    private String messageId;
    
    /** 所属会话ID */
    @TableField("conversation_id")
    private String conversationId;
    
    /** 消息类型: USER-用户, ASSISTANT-AI助手 */
    @TableField("type")
    private String type;
    
    /** 消息内容 */
    @TableField("content")
    private String content;
    
    /**
     * RAG 检索来源引用 JSON（仅 ASSISTANT 消息有值）
     * 格式: [{docId, docName, chunkId, chunkContent, score}]
     */
    @TableField("rag_references")
    private String ragReferences;
    
    /** 元数据(JSON格式)，包含token数、耗时等 */
    @TableField("metadata")
    private String metadata;
    
    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
