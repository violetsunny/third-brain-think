package top.kdla.framework.llm.mentor.rag.mapper;

import top.kdla.framework.llm.mentor.rag.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段 Mapper
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {
}
