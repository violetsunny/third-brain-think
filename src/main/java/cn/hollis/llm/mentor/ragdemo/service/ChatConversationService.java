package cn.hollis.llm.mentor.ragdemo.service;

import cn.hollis.llm.mentor.ragdemo.entity.ChatConversation;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI对话会话服务接口
 */
public interface ChatConversationService {
    
    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param title  会话标题（可选）
     * @return 会话ID
     */
    String createConversation(String userId, String title);
    
    /**
     * 获取用户的会话列表，按更新时间倒序
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    List<ChatConversation> getConversationsByUserId(String userId);

    /**
     * 分页获取用户的会话列表，按更新时间倒序
     *
     * @param userId   用户ID
     * @param page     页码（从1开始）
     * @param pageSize 每页大小
     * @return 分页会话列表
     */
    IPage<ChatConversation> listByUserId(String userId, long page, long pageSize);

    /**
     * 按创建时间范围分页获取用户会话列表（两端参数可选）
     *
     * @param userId    用户ID
     * @param startTime 开始时间（null 表示不过滤下限）
     * @param endTime   结束时间（null 表示不过滤上限）
     * @param page      页码（从1开始）
     * @param pageSize  每页大小
     * @return 分页会话列表
     */
    IPage<ChatConversation> listByTimeRange(String userId, LocalDateTime startTime,
                                            LocalDateTime endTime, long page, long pageSize);
    
    /**
     * 根据会话ID获取会话详情
     *
     * @param conversationId 会话ID
     * @return 会话信息
     */
    ChatConversation getByConversationId(String conversationId);
    
    /**
     * 更新会话标题
     *
     * @param conversationId 会话ID
     * @param title          新标题
     */
    void updateTitle(String conversationId, String title);

    /**
     * 重命名会话（含参数校验：1-50 字符）
     *
     * @param conversationId 会话ID
     * @param title          新标题
     * @throws IllegalArgumentException 当 title 为空或超过 50 字符时
     * @throws jakarta.ws.rs.NotFoundException 当 conversationId 不存在时（实际返回 404 需在 Controller 层处理）
     */
    void renameConversation(String conversationId, String title);

    /**
     * 级联删除会话：删除 chat_message + chat_conversation + 清除 Redis 缓存（事务保证）
     *
     * @param conversationId 会话ID
     * @throws RuntimeException 当 conversationId 不存在时抛出（Controller 层捕获返回 404）
     */
    void deleteConversationCascade(String conversationId);
    
    /**
     * 增加消息计数
     *
     * @param conversationId 会话ID
     */
    void incrementMessageCount(String conversationId);
    
    /**
     * 删除会话（软删除）
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean deleteConversation(String conversationId);
}

