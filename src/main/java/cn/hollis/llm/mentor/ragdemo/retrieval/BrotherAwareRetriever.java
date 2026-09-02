package cn.hollis.llm.mentor.ragdemo.retrieval;

import cn.hollis.llm.mentor.ragdemo.entity.KnowledgeSegment;
import cn.hollis.llm.mentor.ragdemo.mapper.KnowledgeSegmentMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 兄弟分片感知检索器
 * <p>
 * 在普通向量检索的基础上，自动补充相关的兄弟分段和父分段内容，提升检索结果的完整性。
 * <p>
 * <b>工作流程：</b>
 * <ol>
 *   <li>执行基础向量检索，获取初始匹配结果</li>
 *   <li>分析结果的元数据，收集 brotherChunkId 和 parentChunkId</li>
 *   <li>批量查询相关的兄弟分段和父分段</li>
 *   <li>合并所有相关内容，去重后返回</li>
 * </ol>
 * <p>
 * <b>元数据字段：</b>
 * <ul>
 *   <li>{@code brotherChunkId} - 兄弟分组ID，同组分段共享</li>
 *   <li>{@code brotherChunkIndex} - 在兄弟组中的序号（从1开始）</li>
 *   <li>{@code brotherChunkTotal} - 兄弟组总分段数</li>
 *   <li>{@code parentChunkId} - 父分段ID</li>
 *   <li>{@code chunkId} - 分段唯一ID</li>
 * </ul>
 *
 * @author Hollis (adapted for rag-demo)
 */
@Slf4j
@Component
public class BrotherAwareRetriever {

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired(required = false)
    private ElasticsearchClient elasticsearchClient;

    @Autowired(required = false)
    private KnowledgeSegmentMapper knowledgeSegmentMapper;

    // 元数据键常量
    private static final String CHUNK_ID = "chunkId";
    private static final String PARENT_CHUNK_ID = "parentChunkId";
    private static final String BROTHER_CHUNK_ID = "brotherChunkId";
    private static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";
    private static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    private static final String ES_INDEX = "rag_demo_index";

    /**
     * 执行兄弟感知的检索
     *
     * @param queryEmbedding 查询向量
     * @param maxResults     最大返回结果数
     * @param minScore       最小相似度分数
     * @return 增强后的检索结果列表
     */
    public List<EnhancedContent> retrieveWithBrothers(
            Embedding queryEmbedding,
            int maxResults,
            double minScore) {

        log.info("Starting brother-aware retrieval: maxResults={}, minScore={}", maxResults, minScore);

        // 1. 执行基础向量检索
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> baseResult = embeddingStore.search(request);
        List<EmbeddingMatch<TextSegment>> initialMatches = baseResult.matches();

        log.info("Base retrieval returned {} matches", initialMatches.size());

        if (initialMatches.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 收集需要补充的 chunk IDs
        Set<String> brotherChunkIds = new HashSet<>();
        Set<String> parentChunkIds = new HashSet<>();
        Set<String> retrievedChunkIds = new HashSet<>();

        for (EmbeddingMatch<TextSegment> match : initialMatches) {
            TextSegment segment = match.embedded();
            String chunkId = segment.metadata().getString(CHUNK_ID);
            String brotherId = segment.metadata().getString(BROTHER_CHUNK_ID);
            String parentId = segment.metadata().getString(PARENT_CHUNK_ID);

            if (chunkId != null) retrievedChunkIds.add(chunkId);
            if (brotherId != null) brotherChunkIds.add(brotherId);
            if (parentId != null) parentChunkIds.add(parentId);
        }

        log.info("Found {} brother groups and {} parent chunks to fetch",
                brotherChunkIds.size(), parentChunkIds.size());

        // 3. 批量查询兄弟和父分段
        List<EmbeddingMatch<TextSegment>> relatedMatches = fetchRelatedContents(
                brotherChunkIds, parentChunkIds, retrievedChunkIds, initialMatches);

        log.info("Fetched {} related segments", relatedMatches.size());

        // 4. 合并所有结果
        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>(initialMatches);
        allMatches.addAll(relatedMatches);

        // 5. 转换为增强内容并排序
        List<EnhancedContent> enhancedContents = allMatches.stream()
                .map(match -> createEnhancedContent(match, initialMatches))
                .distinct()
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(maxResults * 2L)
                .toList();

        log.info("Final enhanced results: {} contents", enhancedContents.size());
        return enhancedContents;
    }

    /**
     * 批量查询相关的兄弟和父分段。
     * <p>
     * 兄弟 chunks: ES terms query on brotherChunkId field.
     * 父 chunks:   MySQL KnowledgeSegmentMapper lookup by segmentId.
     * Parent text replaces the child's TextSegment content (task 5.4).
     */
    private List<EmbeddingMatch<TextSegment>> fetchRelatedContents(
            Set<String> brotherChunkIds,
            Set<String> parentChunkIds,
            Set<String> excludeChunkIds,
            List<EmbeddingMatch<TextSegment>> initialMatches) {

        List<EmbeddingMatch<TextSegment>> relatedMatches = new ArrayList<>();

        // ── 5.2: ES terms query for brother chunks ──────────────────
        if (elasticsearchClient != null && !brotherChunkIds.isEmpty()) {
            try {
                List<String> ids = new ArrayList<>(brotherChunkIds);
                var searchResponse = elasticsearchClient.search(s -> s
                        .index(ES_INDEX)
                        .query(q -> q
                                .terms(t -> t
                                        .field("metadata." + BROTHER_CHUNK_ID + ".keyword")
                                        .terms(tv -> tv.value(
                                                ids.stream()
                                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                                        .toList()
                                        ))
                                )
                        )
                        .size(brotherChunkIds.size() * 10),
                        Map.class
                );

                for (var hit : searchResponse.hits().hits()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = (Map<String, Object>) hit.source();
                    if (source == null) continue;

                    String content = (String) source.get("content");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) source.getOrDefault("metadata", Map.of());
                    String chunkId = (String) meta.get(CHUNK_ID);

                    if (chunkId != null && excludeChunkIds.contains(chunkId)) continue;

                    dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
                    meta.forEach((k, v) -> metadata.put(k, v == null ? "" : v.toString()));

                    TextSegment segment = TextSegment.from(content != null ? content : "", metadata);
                    relatedMatches.add(new EmbeddingMatch<>(0.0, chunkId, Embedding.from(new float[0]), segment));
                }
                log.debug("ES brother query returned {} segments", relatedMatches.size());

            } catch (Exception e) {
                log.warn("ES brother query failed: {}", e.getMessage());
            }
        }

        // ── 5.3 & 5.4: MySQL parent chunk lookup — replace child content ──
        if (knowledgeSegmentMapper != null && !parentChunkIds.isEmpty()) {
            Map<String, String> parentTextCache = new HashMap<>();

            for (String parentId : parentChunkIds) {
                if (parentTextCache.containsKey(parentId)) continue;
                try {
                    KnowledgeSegment parentSegment = knowledgeSegmentMapper.selectOne(
                            new LambdaQueryWrapper<KnowledgeSegment>()
                                    .eq(KnowledgeSegment::getSegmentId, parentId)
                    );
                    if (parentSegment != null && parentSegment.getContent() != null) {
                        parentTextCache.put(parentId, parentSegment.getContent());
                    }
                } catch (Exception e) {
                    log.warn("Failed to load parent chunk {}: {}", parentId, e.getMessage());
                }
            }

            // Replace child TextSegment content with parent text for initial matches
            for (EmbeddingMatch<TextSegment> match : initialMatches) {
                String parentId = match.embedded().metadata().getString(PARENT_CHUNK_ID);
                if (parentId == null || !parentTextCache.containsKey(parentId)) continue;

                String parentText = parentTextCache.get(parentId);
                dev.langchain4j.data.document.Metadata metadata = match.embedded().metadata();
                // Create a new match with parent text substituted (task 5.4)
                TextSegment parentSeg = TextSegment.from(parentText, metadata);
                relatedMatches.add(new EmbeddingMatch<>(match.score(), match.embeddingId(), match.embedding(), parentSeg));
                log.debug("Substituted parent text for chunk: {}", parentId);
            }
        }

        // Filter out already-retrieved chunks
        relatedMatches.removeIf(m -> {
            String cid = m.embedded().metadata().getString(CHUNK_ID);
            return cid != null && excludeChunkIds.contains(cid);
        });

        return relatedMatches;
    }

    /**
     * 创建增强内容对象
     */
    private EnhancedContent createEnhancedContent(
            EmbeddingMatch<TextSegment> match,
            List<EmbeddingMatch<TextSegment>> initialMatches) {

        TextSegment segment = match.embedded();
        Map<String, Object> metadata = segment.metadata().toMap();

        boolean isInitialResult = initialMatches.stream()
                .anyMatch(m -> {
                    String cid = m.embedded().metadata().getString(CHUNK_ID);
                    return cid != null && cid.equals(metadata.get(CHUNK_ID));
                });

        return new EnhancedContent(
                segment.text(),
                metadata,
                match.score(),
                isInitialResult ? "direct" : "related",
                metadata.containsKey(BROTHER_CHUNK_ID) ?
                    metadata.get(BROTHER_CHUNK_ID).toString() : null,
                metadata.containsKey(PARENT_CHUNK_ID) ?
                    metadata.get(PARENT_CHUNK_ID).toString() : null
        );
    }

    /**
     * 增强内容对象
     * 包含原始内容和元数据信息
     */
    public static class EnhancedContent {
        private final String content;
        private final Map<String, Object> metadata;
        private final double relevanceScore;
        private final String sourceType;  // "direct" or "related"
        private final String brotherChunkId;
        private final String parentChunkId;

        public EnhancedContent(String content,
                              Map<String, Object> metadata,
                              double relevanceScore,
                              String sourceType,
                              String brotherChunkId,
                              String parentChunkId) {
            this.content = content;
            this.metadata = metadata;
            this.relevanceScore = relevanceScore;
            this.sourceType = sourceType;
            this.brotherChunkId = brotherChunkId;
            this.parentChunkId = parentChunkId;
        }

        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getRelevanceScore() { return relevanceScore; }
        public String getSourceType() { return sourceType; }
        public String getBrotherChunkId() { return brotherChunkId; }
        public String getParentChunkId() { return parentChunkId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EnhancedContent that = (EnhancedContent) o;
            return Objects.equals(metadata.get(CHUNK_ID), that.metadata.get(CHUNK_ID));
        }

        @Override
        public int hashCode() {
            return Objects.hash(metadata.get(CHUNK_ID));
        }
    }
}
