package top.kdla.framework.llm.mentor.rag.versioning;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档版本 Mapper
 */
@Mapper
public interface KnowledgeDocumentVersionMapper extends BaseMapper<KnowledgeDocumentVersion> {
}
