package cn.hollis.llm.mentor.ragdemo.mapper;

import cn.hollis.llm.mentor.ragdemo.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段 Mapper
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {
}
