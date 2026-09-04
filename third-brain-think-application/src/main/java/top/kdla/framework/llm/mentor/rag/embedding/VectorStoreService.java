package top.kdla.framework.llm.mentor.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import top.kdla.framework.llm.mentor.rag.constant.DocumentStatus;
import top.kdla.framework.llm.mentor.rag.mapper.KnowledgeDocumentMapper;
import top.kdla.framework.llm.mentor.rag.retrieval.RetrievalCacheInvalidator;
import top.kdla.framework.llm.mentor.rag.entity.KnowledgeDocument;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量存储服务
 *
 * <p>Milvus 为必须组件，始终作为主向量存储；
 * Elasticsearch 为可选组件（elasticsearch.enable=true 时注入），
 * 激活后同步写入以支持向量 + BM25 双路检索。
 * ES 写入失败不影响主流程，仅记录 ERROR 日志。
 */
@Slf4j
@Service
public class VectorStoreService {

    @Autowired
    private KnowledgeDocumentMapper documentMapper;

    @Autowired
    private RetrievalCacheInvalidator retrievalCacheInvalidator;

    // ── Milvus 配置（必须）──
    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private int milvusPort;

    @Value("${milvus.collection:rag_demo_collection}")
    private String milvusCollection;

    // ── ES（可选）：由 ElasticsearchConfig 创建的 Bean，条件注入 ──
    @Autowired(required = false)
    private ElasticsearchEmbeddingStore elasticsearchEmbeddingStore;

    // ── 内部 Store 引用 ──
    private MilvusEmbeddingStore milvusStore;

    /**
     * 应用启动后初始化 Milvus（必须）向量存储。
     * ES Store 已由 Spring 条件注入，无需手动初始化。
     */
    @PostConstruct
    public void initStores() {
        log.info("Initializing Milvus embedding store (required)...");
        milvusStore = MilvusEmbeddingStore.builder()
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(milvusCollection)
                .dimension(1536)
                .build();
        log.info("Milvus embedding store initialized: {}:{}/{}", milvusHost, milvusPort, milvusCollection);

        if (elasticsearchEmbeddingStore != null) {
            log.info("[Component] elasticsearch: active (optional, dual-write enabled)");
        } else {
            log.info("[Component] elasticsearch: disabled (keyword retrieval falls back to MySQL LIKE)");
        }
    }

    /**
     * 添加文档片段到向量存储。
     * Milvus 写入失败向上抛出异常；ES 写入失败仅记录 ERROR，不抛异常。
     */
    public void addSegments(List<TextSegment> segments, dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
        log.info("Adding {} segments to vector stores", segments.size());

        // 分离需要生成向量的分段和只需要存储文本的分段
        List<TextSegment> segmentsToEmbed = new ArrayList<>();
        List<TextSegment> textOnlySegments = new ArrayList<>();

        for (TextSegment segment : segments) {
            String skipEmbeddingStr = segment.metadata().getString("skipEmbedding");
            boolean skipEmbedding = "true".equalsIgnoreCase(skipEmbeddingStr);
            if (skipEmbedding) {
                textOnlySegments.add(segment);
                log.debug("Skipping embedding for parent segment: chunkId={}",
                        segment.metadata().getString("chunkId"));
            } else {
                segmentsToEmbed.add(segment);
            }
        }

        log.info("Segments to embed: {}, text-only segments: {}",
                segmentsToEmbed.size(), textOnlySegments.size());

        // 批量生成 embeddings（只针对需要嵌入的分段）
        List<Embedding> embeddings = new ArrayList<>();
        int batchSize = 10;

        for (int i = 0; i < segmentsToEmbed.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segmentsToEmbed.size());
            List<TextSegment> batch = segmentsToEmbed.subList(i, end);
            List<Embedding> batchEmbeddings = embeddingModel.embedAll(batch).content();
            embeddings.addAll(batchEmbeddings);
        }

        // 父分段不写入向量存储
        if (!textOnlySegments.isEmpty()) {
            log.info("Skipping {} parent/text-only segments from vector stores (stored in MySQL only)",
                    textOnlySegments.size());
        }

        if (embeddings.isEmpty()) {
            return;
        }

        // 写入 Milvus（主，必须）— 失败向上抛出
        List<String> ids = milvusStore.addAll(embeddings, segmentsToEmbed);
        log.info("Added {} segments with embeddings to Milvus", ids.size());

        // 写入 ES（可选附加）— 失败仅记录 ERROR，不抛异常
        if (elasticsearchEmbeddingStore != null) {
            try {
                elasticsearchEmbeddingStore.addAll(embeddings, segmentsToEmbed);
                log.info("Added {} segments with embeddings to Elasticsearch", embeddings.size());
            } catch (Exception e) {
                log.error("Failed to add segments to Elasticsearch (optional, main flow unaffected): {}",
                        e.getMessage(), e);
            }
        }
    }

    /**
     * 向量相似度搜索（始终使用 Milvus）
     */
    public List<EmbeddingMatch<TextSegment>> searchSimilar(
            Embedding queryEmbedding,
            int maxResults,
            double minScore) {

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result = milvusStore.search(request);
        log.info("Milvus search returned {} results", result.matches().size());
        return new ArrayList<>(result.matches());
    }

    public boolean isMilvusEnabled() {
        return milvusStore != null;
    }

    public boolean isEsEnabled() {
        return elasticsearchEmbeddingStore != null;
    }

    /**
     * 按 document_id 元数据删除 Milvus 中对应的向量记录
     */
    public void deleteByDocumentId(String docId) {
        try {
            milvusStore.removeAll(dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                    .metadataKey("document_id").isEqualTo(docId));
            log.info("Deleted Milvus vectors for docId={}", docId);
        } catch (Exception e) {
            log.warn("Failed to delete Milvus vectors for docId={}: {}", docId, e.getMessage());
        }
    }

    /**
     * 异步嵌入文档片段
     * 状态流转: SPLITTED → EMBEDDING → EMBEDDED / EMBED_FAILED
     */
    @Async("embeddingExecutor")
    public void addSegmentsAsync(String docId, List<TextSegment> segments,
                                 dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
        log.info("异步嵌入开始: docId={}, segmentCount={}", docId, segments.size());

        // 1. 更新状态为 EMBEDDING
        updateDocStatus(docId, DocumentStatus.EMBEDDING.name());

        try {
            // 2. 执行同步嵌入写入
            addSegments(segments, embeddingModel);

            // 3. 成功：更新状态为 EMBEDDED
            updateDocStatus(docId, DocumentStatus.EMBEDDED.name());
            log.info("异步嵌入完成: docId={}", docId);

            // 4. 清除检索缓存
            retrievalCacheInvalidator.invalidateByKnowledgeBaseType("default");

        } catch (Exception e) {
            // 5. 失败：更新状态为 EMBED_FAILED
            log.error("异步嵌入失败: docId={}", docId, e);
            updateDocStatus(docId, DocumentStatus.EMBED_FAILED.name());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private void updateDocStatus(String docId, String status) {
        try {
            LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeDocument::getDocId, docId);
            KnowledgeDocument update = new KnowledgeDocument();
            update.setStatus(status);
            documentMapper.update(update, wrapper);
            log.debug("文档状态更新: docId={}, status={}", docId, status);
        } catch (Exception e) {
            log.warn("文档状态更新失败: docId={}, status={}, error={}", docId, status, e.getMessage());
        }
    }
}

