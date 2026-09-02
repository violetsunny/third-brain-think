package cn.hollis.llm.mentor.ragdemo.retrieval;

import cn.hollis.llm.mentor.ragdemo.embedding.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.copyIfNotNull;
import static dev.langchain4j.internal.ValidationUtils.*;

/**
 * 向量检索器 - 基于LangChain4j ContentRetriever接口
 * 使用向量相似度进行检索
 */
@Slf4j
public class VectorContentRetriever implements ContentRetriever {

    private final VectorStoreService vectorStoreService;
    private final EmbeddingModel embeddingModel;
    private final Integer maxResults;
    private final Double minScore;

    @Builder
    public VectorContentRetriever(VectorStoreService vectorStoreService,
                                  EmbeddingModel embeddingModel,
                                  Integer maxResults,
                                  Double minScore) {
        this.vectorStoreService = ensureNotNull(vectorStoreService, "vectorStoreService");
        this.embeddingModel = ensureNotNull(embeddingModel, "embeddingModel");
        this.maxResults = ensureGreaterThanZero(maxResults, "maxResults");
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        log.debug("执行向量检索: query={}, maxResults={}, minScore={}", 
                query.text(), maxResults, minScore);

        try {
            // 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

            // 执行向量检索
            List<EmbeddingMatch<TextSegment>> matches = vectorStoreService.searchSimilar(
                    queryEmbedding, 
                    maxResults, 
                    minScore != null ? minScore : 0.0
            );

            // 转换为Content列表
            List<Content> contents = matches.stream()
                    .map(match -> Content.from(match.embedded()))
                    .collect(Collectors.toList());

            log.debug("向量检索完成，返回{}条结果", contents.size());
            return contents;

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return List.of();
        }
    }

    /**
     * 创建构建器
     */
    public static VectorContentRetrieverBuilder builder() {
        return new VectorContentRetrieverBuilder();
    }
}
