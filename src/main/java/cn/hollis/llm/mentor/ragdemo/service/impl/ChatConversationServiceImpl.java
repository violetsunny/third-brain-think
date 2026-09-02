package cn.hollis.llm.mentor.ragdemo.service.impl;

import cn.hollis.llm.mentor.ragdemo.entity.ChatConversation;
import cn.hollis.llm.mentor.ragdemo.mapper.ChatConversationMapper;
import cn.hollis.llm.mentor.ragdemo.mapper.ChatMessageMapper;
import cn.hollis.llm.mentor.ragdemo.memory.DatabaseChatMemoryStore;
import cn.hollis.llm.mentor.ragdemo.service.ChatConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConversationServiceImpl implements ChatConversationService {
    
    private final ChatConversationMapper chatConversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final DatabaseChatMemoryStore chatMemoryStore;
    
    @Override
    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString();
        
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle(title != null && !title.isBlank() ? title : null);
        conversation.setStatus("ACTIVE");
        conversation.setMessageCount(0);
        conversation.setCreateTime(LocalDateTime.now());
        conversation.setUpdateTime(LocalDateTime.now());
        
        chatConversationMapper.insert(conversation);
        log.info("Created conversation: {}, user: {}, title: {}", conversationId, userId, conversation.getTitle());
        
        return conversationId;
    }
    
    @Override
    public List<ChatConversation> getConversationsByUserId(String userId) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversation::getUserId, userId)
               .eq(ChatConversation::getStatus, "ACTIVE")
               .orderByDesc(ChatConversation::getUpdateTime);
        
        List<ChatConversation> list = chatConversationMapper.selectList(wrapper);
        list.forEach(this::normalizeTitle);
        return list;
    }

    @Override
    public IPage<ChatConversation> listByUserId(String userId, long page, long pageSize) {
        Page<ChatConversation> pageObj = new Page<>(page, pageSize);
        IPage<ChatConversation> result = chatConversationMapper.selectPageByUserId(pageObj, userId);
        result.getRecords().forEach(this::normalizeTitle);
        return result;
    }

    @Override
    public IPage<ChatConversation> listByTimeRange(String userId, LocalDateTime startTime,
                                                    LocalDateTime endTime, long page, long pageSize) {
        Page<ChatConversation> pageObj = new Page<>(page, pageSize);
        IPage<ChatConversation> result;
        // If neither start nor end, fall back to normal list
        if (startTime == null && endTime == null) {
            result = chatConversationMapper.selectPageByUserId(pageObj, userId);
        } else {
            result = chatConversationMapper.selectByTimeRange(pageObj, userId, startTime, endTime);
        }
        result.getRecords().forEach(this::normalizeTitle);
        return result;
    }
    
    @Override
    public ChatConversation getByConversationId(String conversationId) {
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversation::getConversationId, conversationId);
        ChatConversation conversation = chatConversationMapper.selectOne(wrapper);
        if (conversation != null) normalizeTitle(conversation);
        return conversation;
    }
    
    @Override
    public void updateTitle(String conversationId, String title) {
        ChatConversation conversation = getByConversationId(conversationId);
        if (conversation != null) {
            conversation.setTitle(title);
            conversation.setUpdateTime(LocalDateTime.now());
            chatConversationMapper.updateById(conversation);
            log.info("Updated conversation title: {}, new title: {}", conversationId, title);
        }
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        if (title == null || title.isBlank() || title.length() > 50) {
            throw new IllegalArgumentException("title 长度必须在 1-50 字符之间");
        }
        ChatConversation conversation = getByConversationId(conversationId);
        if (conversation == null) {
            throw new RuntimeException("会话不存在: " + conversationId);
        }
        conversation.setTitle(title);
        conversation.setUpdateTime(LocalDateTime.now());
        chatConversationMapper.updateById(conversation);
        log.info("Renamed conversation: {} -> {}", conversationId, title);
    }

    @Override
    @Transactional
    public void deleteConversationCascade(String conversationId) {
        ChatConversation conversation = getByConversationId(conversationId);
        if (conversation == null) {
            throw new RuntimeException("会话不存在: " + conversationId);
        }

        // Step 1: 删除 chat_message 记录
        cn.hollis.llm.mentor.ragdemo.entity.ChatMessage msg = new cn.hollis.llm.mentor.ragdemo.entity.ChatMessage();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<cn.hollis.llm.mentor.ragdemo.entity.ChatMessage> msgWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        msgWrapper.eq(cn.hollis.llm.mentor.ragdemo.entity.ChatMessage::getConversationId, conversationId);
        int msgDeleted = chatMessageMapper.delete(msgWrapper);
        log.info("Cascade deleted {} messages for conversation: {}", msgDeleted, conversationId);

        // Step 2: 删除 chat_conversation 记录
        chatConversationMapper.deleteById(conversation.getId());
        log.info("Deleted conversation: {}", conversationId);

        // Step 3: 清除 Redis 缓存
        try {
            chatMemoryStore.evictCache(conversationId);
            log.info("Evicted Redis cache for conversation: {}", conversationId);
        } catch (Exception e) {
            log.warn("Failed to evict Redis cache for conversation: {}: {}", conversationId, e.getMessage());
        }
    }
    
    @Override
    public void incrementMessageCount(String conversationId) {
        ChatConversation conversation = getByConversationId(conversationId);
        if (conversation != null) {
            conversation.setMessageCount(conversation.getMessageCount() + 1);
            conversation.setLastMessageTime(LocalDateTime.now());
            conversation.setUpdateTime(LocalDateTime.now());
            chatConversationMapper.updateById(conversation);
        }
    }
    
    @Override
    @Transactional
    public boolean deleteConversation(String conversationId) {
        ChatConversation conversation = getByConversationId(conversationId);
        if (conversation != null) {
            conversation.setStatus("DELETED");
            conversation.setUpdateTime(LocalDateTime.now());
            chatConversationMapper.updateById(conversation);
            log.info("Soft-deleted conversation: {}", conversationId);
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /** 将 null title 替换为"未命名会话"（不持久化，仅用于响应组装） */
    private void normalizeTitle(ChatConversation conversation) {
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()) {
            conversation.setTitle("未命名会话");
        }
    }
}

