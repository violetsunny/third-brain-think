package top.kdla.framework.llm.mentor.rag.mapper;

import top.kdla.framework.llm.mentor.rag.entity.KnowledgeDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 按 docId 列表批量查询文档状态（用于批量状态查询接口）
     */
    @Select("<script>" +
            "SELECT * FROM knowledge_document WHERE doc_id IN " +
            "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<KnowledgeDocument> selectByDocIds(@Param("docIds") List<String> docIds);
}
