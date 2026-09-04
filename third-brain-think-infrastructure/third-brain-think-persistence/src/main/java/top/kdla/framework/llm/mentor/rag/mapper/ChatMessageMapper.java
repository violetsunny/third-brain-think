package top.kdla.framework.llm.mentor.rag.mapper;

import top.kdla.framework.llm.mentor.rag.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI对话消息 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 分页查询指定会话的消息，按 create_time 升序
     */
    @Select("SELECT * FROM chat_message WHERE conversation_id = #{conversationId} ORDER BY create_time ASC")
    IPage<ChatMessage> selectPageByConversationId(IPage<ChatMessage> page,
                                                  @Param("conversationId") String conversationId);
}
