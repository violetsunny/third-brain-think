package cn.hollis.llm.mentor.ragdemo.service.impl;

import cn.hollis.llm.mentor.ragdemo.entity.ChatMessage;
import cn.hollis.llm.mentor.ragdemo.mapper.ChatMessageMapper;
import cn.hollis.llm.mentor.ragdemo.service.ChatConversationService;
import cn.hollis.llm.mentor.ragdemo.service.ChatMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话消息服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {
    
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConversationService chatConversationService;
    
    @Override
    public String saveUserMessage(String conversationId, String content) {
        String messageId = UUID.randomUUID().toString();
        
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType("USER");
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        message.setUpdateTime(LocalDateTime.now());
        
        chatMessageMapper.insert(message);
        
        // 更新会话的消息计数
        chatConversationService.incrementMessageCount(conversationId);
        
        log.info("Saved user message: {}, conversation: {}", messageId, conversationId);
        return messageId;
    }
    
    @Override
    public String saveAssistantMessage(String conversationId, String content) {
        String messageId = UUID.randomUUID().toString();
        
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType("ASSISTANT");
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        message.setUpdateTime(LocalDateTime.now());
        
        chatMessageMapper.insert(message);
        
        // 更新会话的消息计数
        chatConversationService.incrementMessageCount(conversationId);
        
        log.info("Saved assistant message: {}, conversation: {}", messageId, conversationId);
        return messageId;
    }
    
    @Override
    public List<ChatMessage> getMessagesByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId)
               .orderByAsc(ChatMessage::getCreateTime);
        
        return chatMessageMapper.selectList(wrapper);
    }

    @Override
    public IPage<ChatMessage> getMessagesByConversationId(String conversationId, long page, long pageSize) {
        Page<ChatMessage> pageObj = new Page<>(page, pageSize);
        return chatMessageMapper.selectPageByConversationId(pageObj, conversationId);
    }
    
    @Override
    public void deleteMessagesByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId);
        chatMessageMapper.delete(wrapper);
        log.info("Deleted all messages for conversation: {}", conversationId);
    }

    @Override
    public void updateRagReferences(String messageId, String ragReferencesJson) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getMessageId, messageId);
        ChatMessage update = new ChatMessage();
        update.setRagReferences(ragReferencesJson);
        chatMessageMapper.update(update, wrapper);
        log.debug("Updated rag_references for message: {}", messageId);
    }
}
