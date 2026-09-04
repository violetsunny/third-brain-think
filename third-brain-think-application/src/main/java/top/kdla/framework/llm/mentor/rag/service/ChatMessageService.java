package top.kdla.framework.llm.mentor.rag.service;

import top.kdla.framework.llm.mentor.rag.entity.ChatMessage;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * AI对话消息服务接口
 */
public interface ChatMessageService {
    
    /**
     * 保存用户消息
     *
     * @param conversationId 会话ID
     * @param content        消息内容
     * @return 消息ID
     */
    String saveUserMessage(String conversationId, String content);
    
    /**
     * 保存AI助手消息
     *
     * @param conversationId 会话ID
     * @param content        消息内容
     * @return 消息ID
     */
    String saveAssistantMessage(String conversationId, String content);
    
    /**
     * 获取会话的消息列表，按创建时间正序
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<ChatMessage> getMessagesByConversationId(String conversationId);

    /**
     * 分页获取会话的消息列表，按创建时间正序
     *
     * @param conversationId 会话ID
     * @param page           页码（从1开始）
     * @param pageSize       每页大小（最大100）
     * @return 分页消息列表
     */
    IPage<ChatMessage> getMessagesByConversationId(String conversationId, long page, long pageSize);
    
    /**
     * 删除会话的所有消息
     *
     * @param conversationId 会话ID
     */
    void deleteMessagesByConversationId(String conversationId);

    /**
     * 更新消息的 RAG 引用（检索来源）
     *
     * @param messageId          消息ID
     * @param ragReferencesJson  引用列表 JSON 字符串
     */
    void updateRagReferences(String messageId, String ragReferencesJson);
}
