package cn.hollis.llm.mentor.ragdemo.retrieval;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 混合检索 ContentRetriever 包装器
 * 将 HybridRetrievalService 适配为 LangChain4j ContentRetriever 接口
 */
@Slf4j
@RequiredArgsConstructor
public class HybridContentRetriever implements ContentRetriever {

    private final HybridRetrievalService hybridRetrievalService;

    @Override
    public List<Content> retrieve(Query query) {
        log.info("HybridContentRetriever.retrieve 被调用: {}", query.text());
        
        try {
            // 执行混合检索
            List<SearchResult> results = hybridRetrievalService.hybridSearch(query.text(), Collections.emptyMap());
            
            // 转换为 LangChain4j Content 对象
            return results.stream()
                    .map(result -> {
                        TextSegment segment = result.getSegment();
                        // 添加分数到元数据
                        Metadata metadata = segment.metadata().copy();
                        if (result.getFineRankScore() != null) {
                            metadata.put("rerank_score", result.getFineRankScore());
                        }
                        if (result.getRrfScore() != null) {
                            metadata.put("rrf_score", result.getRrfScore());
                        }
                        TextSegment enrichedSegment = TextSegment.from(segment.text(), metadata);
                        return Content.from(enrichedSegment);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("检索失败，返回空结果", e);
            return Collections.emptyList();
        }
    }
}
