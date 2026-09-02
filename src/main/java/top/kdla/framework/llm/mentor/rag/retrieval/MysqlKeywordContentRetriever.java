package top.kdla.framework.llm.mentor.rag.retrieval;

import top.kdla.framework.llm.mentor.rag.entity.KnowledgeSegment;
import top.kdla.framework.llm.mentor.rag.mapper.KnowledgeSegmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL LIKE 关键词检索器（ES 降级实现）
 *
 * <p>仅当 {@code elasticsearch.enable=false}（即 {@code ElasticsearchConfig} 未激活，
 * 导致名为 "keywordContentRetriever" 的 Bean 不存在）时自动注册。
 *
 * <p>对 {@code knowledge_segment.content} 字段执行 LIKE 模糊匹配，
 * 精度低于 ES BM25，仅适用于开发/演示环境。生产建议开启 ES。
 */
@Slf4j
@Component("keywordContentRetriever")
@ConditionalOnMissingBean(name = "esKeywordContentRetriever")
@RequiredArgsConstructor
public class MysqlKeywordContentRetriever implements ContentRetriever {

    private final KnowledgeSegmentMapper segmentMapper;

    @Value("${rag.retrieval.keyword-top-k:100}")
    private int keywordTopK;

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query.text();
        log.debug("MySQL LIKE 关键词检索: query={}, topK={}", queryText, keywordTopK);

        if (queryText == null || queryText.isBlank()) {
            return Collections.emptyList();
        }

        try {
            LambdaQueryWrapper<KnowledgeSegment> wrapper = new LambdaQueryWrapper<>();
            wrapper.like(KnowledgeSegment::getContent, queryText)
                   .last("LIMIT " + keywordTopK);

            List<KnowledgeSegment> segments = segmentMapper.selectList(wrapper);

            List<Content> results = segments.stream()
                    .filter(seg -> seg.getContent() != null && !seg.getContent().isBlank())
                    .map(seg -> {
                        Metadata metadata = new Metadata();
                        if (seg.getDocId() != null) {
                            metadata.put("document_id", seg.getDocId());
                        }
                        if (seg.getSegmentId() != null) {
                            metadata.put("segment_id", seg.getSegmentId());
                        }
                        metadata.put("source", "mysql_like");
                        TextSegment textSegment = TextSegment.from(seg.getContent(), metadata);
                        return Content.from(textSegment);
                    })
                    .collect(Collectors.toList());

            log.debug("MySQL LIKE 关键词检索返回 {} 个结果", results.size());
            return results;

        } catch (Exception e) {
            log.error("MySQL LIKE 关键词检索失败", e);
            return Collections.emptyList();
        }
    }
}
