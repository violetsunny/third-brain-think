package top.kdla.framework.llm.mentor.know.engine.chat.mapper;

import top.kdla.framework.llm.mentor.know.engine.chat.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话消息表 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
