package cn.hollis.llm.mentor.ragdemo.mapper;

import cn.hollis.llm.mentor.ragdemo.entity.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * AI对话会话 Mapper
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    /**
     * 分页查询指定用户的会话，按 update_time 降序
     */
    @Select("SELECT * FROM chat_conversation WHERE user_id = #{userId} AND status = 'ACTIVE' ORDER BY update_time DESC")
    IPage<ChatConversation> selectPageByUserId(IPage<ChatConversation> page,
                                               @Param("userId") String userId);

    /**
     * 按 createTime 范围分页查询指定用户的活跃会话（两端可选）
     */
    @Select("<script>" +
            "SELECT * FROM chat_conversation WHERE user_id = #{userId} AND status = 'ACTIVE'" +
            "<if test='start != null'> AND create_time &gt;= #{start}</if>" +
            "<if test='end != null'> AND create_time &lt;= #{end}</if>" +
            " ORDER BY update_time DESC" +
            "</script>")
    IPage<ChatConversation> selectByTimeRange(IPage<ChatConversation> page,
                                              @Param("userId") String userId,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);
}
