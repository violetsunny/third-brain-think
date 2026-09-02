package top.kdla.framework.llm.mentor.rag.retrieval;

import top.kdla.framework.llm.mentor.rag.embedding.VectorStoreService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * 实现：三重过滤 → RRF融合 → 粗排 → 精排 的完整流程
 * 同时实现 ContentRetriever 接口，用于 LangChain4j AiServices
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final RetrievalCacheService retrievalCacheService;
    private final RerankRateLimiter rerankRateLimiter;
    // Reused across calls — RestTemplate is thread-safe after construction.
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Autowired(required = false)
    private ElasticsearchClient elasticsearchClient;

    /** 关键词检索器：ES 激活时为 BM25，否则为 MySQL LIKE 降级实现。可为 null */
    @Autowired(required = false)
    @Qualifier("keywordContentRetriever")
    private ContentRetriever keywordContentRetriever;

    @Value("${rag.retrieval.vector-top-k:100}")
    private int vectorTopK;

    @Value("${rag.retrieval.keyword-top-k:100}")
    private int keywordTopK;

    @Value("${rag.retrieval.metadata-top-k:100}")
    private int metadataTopK;

    @Value("${rag.retrieval.rrf-top-k:20}")
    private int rrfTopK;

    @Value("${rag.retrieval.coarse-top-k:10}")
    private int coarseTopK;

    @Value("${rag.retrieval.fine-top-k:5}")
    private int fineTopK;

    @Value("${rag.rerank.top-n:${rag.retrieval.fine-top-k:5}}")
    private int rerankTopN;

    @Value("${elasticsearch.index-name:rag_demo_index}")
    private String esIndexName;

    @Value("${rag.rerank.api-key:}")
    private String rerankApiKey;

    @Value("${rag.rerank.provider:dashscope}")
    private String rerankProvider;

    // RRF算法常数
    private static final int RRF_K = 60;

    /**
     * 完整的混合检索流程
     *
     * @param query       用户查询
     * @param filters     元数据过滤条件
     * @return 精排后的Top-K文档
     */
    public List<SearchResult> hybridSearch(String query, Map<String, Object> filters) {
        log.info("========== 开始混合检索流程 ==========");
        log.info("查询: {}", query);
        log.info("过滤条件: {}", filters);

        // 缓存 knowledgeBaseType 占位（filters 中取 knowledge_base_type，无则用 "default"）
        String kbType = filters != null && filters.containsKey("knowledge_base_type")
                ? filters.get("knowledge_base_type").toString()
                : "default";

        // Step 0: 检查缓存
        var cached = retrievalCacheService.get(query, kbType);
        if (cached.isPresent()) {
            log.info("检索缓存命中，直接返回: kbType={}, count={}", kbType, cached.get().size());
            return cached.get();
        }

        try {
            // Step 1: 三重过滤（向量 + 关键词 + 元数据），各自Top 100
            List<SearchResult> vectorResults = vectorSearch(query, vectorTopK);
            List<SearchResult> keywordResults = keywordSearch(query, keywordTopK);
            List<SearchResult> metadataResults = metadataSearch(query, filters, metadataTopK);

            log.info("三重过滤结果: 向量={}, 关键词={}, 元数据={}",
                    vectorResults.size(), keywordResults.size(), metadataResults.size());

            // Step 2: RRF列表融合，Top 20
            List<SearchResult> rrfMerged = rrfFusion(vectorResults, keywordResults, metadataResults, rrfTopK);
            log.info("RRF融合后: {} 条结果", rrfMerged.size());

            // Step 3: 粗排 (BM25 + 向量相似度)，Top 10
            List<SearchResult> coarseRanked = coarseRanking(query, rrfMerged, coarseTopK);
            log.info("粗排后: {} 条结果", coarseRanked.size());

            // Step 4: 精排 (Cross-encoder/Qwen-rerank/BGE-Reranker)，Top 5
            List<SearchResult> fineRanked = fineRanking(query, coarseRanked, fineTopK);
            log.info("精排后: {} 条结果", fineRanked.size());

            log.info("========== 混合检索完成 ==========");

            // 写入缓存
            retrievalCacheService.put(query, kbType, fineRanked);

            return fineRanked;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    // ==================== Step 1: 三重过滤 ====================

    /**
     * 向量检索
     */
    private List<SearchResult> vectorSearch(String query, int topK) {
        log.info("执行向量检索, topK={}", topK);

        try {
            // 生成查询向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 使用 VectorStoreService 进行检索（支持 Milvus/ES）
            List<EmbeddingMatch<TextSegment>> matches = vectorStoreService.searchSimilar(
                    queryEmbedding,
                    topK,
                    0.0  // 最低分数阈值，后续可以配置
            );

            // 转换为 SearchResult
            List<SearchResult> results = matches.stream()
                    .map(match -> {
                        TextSegment segment = match.embedded();
                        String docId = segment.metadata().getString("document_id");
                        if (docId == null || docId.isEmpty()) {
                            docId = "unknown";
                        }
                        
                        SearchResult result = new SearchResult();
                        result.setDocId(docId);
                        result.setSegment(segment);
                        result.setVectorScore(match.score());
                        result.setBm25Score(0.0);  // 向量检索没有BM25分数
                        return result;
                    })
                    .collect(Collectors.toList());

            log.info("向量检索完成，返回 {} 条结果", results.size());
            return results;

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 关键词检索 (BM25 / MySQL LIKE 降级)
     *
     * <p>统一通过 {@code keywordContentRetriever} 执行：
     * <ul>
     *   <li>ES 启用且连接正常：{@code keywordContentRetriever} = {@code esKeywordContentRetriever}（BM25）</li>
     *   <li>ES 未启用或连接失败：{@code keywordContentRetriever} = {@code MysqlKeywordContentRetriever}（LIKE 降级）</li>
     *   <li>两者均不可用：跳过，返回空列表</li>
     * </ul>
     */
    private List<SearchResult> keywordSearch(String query, int topK) {
        log.info("执行关键词检索, topK={}", topK);

        if (keywordContentRetriever == null) {
            log.warn("无可用的 keywordContentRetriever（ES 和 MySQL 降级均未注册），跳过关键词检索");
            return new ArrayList<>();
        }

        try {
            log.info("使用 keywordContentRetriever: {}", keywordContentRetriever.getClass().getSimpleName());
            List<Content> contents = keywordContentRetriever.retrieve(Query.from(query));
            List<SearchResult> results = contents.stream()
                    .limit(topK)
                    .map(c -> {
                        String docId = c.textSegment().metadata().getString("document_id");
                        SearchResult r = new SearchResult();
                        r.setDocId(docId != null ? docId : "unknown");
                        r.setSegment(c.textSegment());
                        // BM25 分数从 metadata 取（ES 实现会写入），MySQL LIKE 无精确分数取固定中间值
                        String scoreStr = c.textSegment().metadata().getString("bm25_score");
                        r.setBm25Score(scoreStr != null ? Double.parseDouble(scoreStr) : 0.5);
                        r.setVectorScore(0.0);
                        return r;
                    })
                    .collect(Collectors.toList());
            log.info("关键词检索完成，返回 {} 条结果", results.size());
            return results;
        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 元数据过滤检索
     */
    private List<SearchResult> metadataSearch(String query, Map<String, Object> filters, int topK) {
        log.info("执行元数据检索, filters={}, topK={}", filters, topK);

        if (filters == null || filters.isEmpty()) {
            log.info("无元数据过滤条件，跳过");
            return new ArrayList<>();
        }

        if (elasticsearchClient == null) {
            log.info("Elasticsearch 未启用，跳过元数据过滤检索（无通用降级策略）");
            return new ArrayList<>();
        }

        try {
            // 使用 Elasticsearch 进行元数据过滤 + 全文检索
            String indexName = esIndexName;
            
            // 构建 bool query: filter (元数据) + must (文本匹配)
            var boolQueryBuilder = co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.of(b -> {
                // 添加元数据过滤条件
                filters.forEach((key, value) -> {
                    b.filter(f -> f.term(t -> t
                            .field("metadata." + key)
                            .value(v -> v.stringValue(value.toString()))
                    ));
                });
                
                // 添加文本查询
                b.must(m -> m.multiMatch(mm -> mm
                        .query(query)
                        .fields("content", "content.smart")
                ));
                
                return b;
            });
            
            var searchResponse = elasticsearchClient.search(s -> s
                    .index(indexName)
                    .query(co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.bool(boolQueryBuilder)))
                    .size(topK),
                    Map.class
            );

            // 转换为 SearchResult
            List<SearchResult> results = new ArrayList<>();
            var hits = searchResponse.hits().hits();
            
            for (var hit : hits) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) hit.source();
                if (source == null) continue;
                
                String content = (String) source.get("content");
                String docId = (String) source.get("document_id");
                Double score = hit.score() != null ? hit.score() : 0.0;
                
                Metadata metadata = new Metadata();
                metadata.put("document_id", docId != null ? docId : "unknown");
                metadata.put("source", "metadata_filter");
                TextSegment segment = TextSegment.from(content, metadata);
                
                SearchResult result = new SearchResult();
                result.setDocId(docId != null ? docId : "unknown");
                result.setSegment(segment);
                result.setBm25Score(score);
                result.setVectorScore(0.0);
                
                results.add(result);
            }

            log.info("元数据检索完成，返回 {} 条结果", results.size());
            return results;

        } catch (Exception e) {
            log.error("元数据检索失败", e);
            return new ArrayList<>();
        }
    }

    // ==================== Step 2: RRF列表融合 ====================

    /**
     * RRF (Reciprocal Rank Fusion) 融合多路检索结果
     * 公式: RRF Score = Σ(1/(k + rank_i))
     *
     * @param vectorResults   向量检索结果
     * @param keywordResults  关键词检索结果
     * @param metadataResults 元数据检索结果
     * @param topK            返回数量
     * @return 融合后的结果列表
     */
    private List<SearchResult> rrfFusion(List<SearchResult> vectorResults,
                                         List<SearchResult> keywordResults,
                                         List<SearchResult> metadataResults,
                                         int topK) {
        log.info("执行RRF融合, k={}, topK={}", RRF_K, topK);

        // 存储每个docId的RRF分数
        Map<String, Double> rrfScores = new HashMap<>();
        // 存储docId到SearchResult的映射
        Map<String, SearchResult> docMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult result = vectorResults.get(i);
            String docId = result.getDocId();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);

            rrfScores.merge(docId, score, Double::sum);

            if (!docMap.containsKey(docId)) {
                result.setSource("VECTOR");
                result.setOriginalRank(rank);
                docMap.put(docId, result);
            }
        }

        // 处理关键词检索结果
        for (int i = 0; i < keywordResults.size(); i++) {
            SearchResult result = keywordResults.get(i);
            String docId = result.getDocId();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);

            rrfScores.merge(docId, score, Double::sum);

            if (!docMap.containsKey(docId)) {
                result.setSource("KEYWORD");
                result.setOriginalRank(rank);
                docMap.put(docId, result);
            } else {
                // 如果已存在，更新为多来源
                SearchResult existing = docMap.get(docId);
                existing.setSource(existing.getSource() + "+KEYWORD");
            }
        }

        // 处理元数据检索结果
        for (int i = 0; i < metadataResults.size(); i++) {
            SearchResult result = metadataResults.get(i);
            String docId = result.getDocId();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);

            rrfScores.merge(docId, score, Double::sum);

            if (!docMap.containsKey(docId)) {
                result.setSource("METADATA");
                result.setOriginalRank(rank);
                docMap.put(docId, result);
            } else {
                SearchResult existing = docMap.get(docId);
                existing.setSource(existing.getSource() + "+METADATA");
            }
        }

        // 按RRF分数降序排序，取Top K
        List<SearchResult> mergedResults = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    SearchResult result = docMap.get(entry.getKey());
                    result.setRrfScore(entry.getValue());
                    return result;
                })
                .collect(Collectors.toList());

        // 打印RRF分数
        String scoresLog = mergedResults.stream()
                .map(r -> String.format("docId=%s, RRF=%.4f, source=%s",
                        r.getDocId(), r.getRrfScore(), r.getSource()))
                .collect(Collectors.joining("; "));
        log.info("RRF融合Top{}结果: {}", topK, scoresLog);

        return mergedResults;
    }

    // ==================== Step 3: 粗排 ====================

    /**
     * 粗排: 基于BM25分数和向量相似度的加权组合
     *
     * @param query   查询文本
     * @param results RRF融合后的结果
     * @param topK    返回数量
     * @return 粗排后的结果
     */
    private List<SearchResult> coarseRanking(String query, List<SearchResult> results, int topK) {
        log.info("执行粗排, topK={}", topK);

        if (results.isEmpty()) {
            return results;
        }

        // 计算每个结果的粗排分数
        // 权重: BM25占0.4, 向量相似度占0.6
        double bm25Weight = 0.4;
        double vectorWeight = 0.6;

        results.forEach(result -> {
            result.calculateCoarseScore(bm25Weight, vectorWeight);
        });

        // 按粗排分数降序排序
        List<SearchResult> ranked = results.stream()
                .sorted(Comparator.comparing(SearchResult::getCoarseRankScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // 打印粗排结果
        String scoresLog = ranked.stream()
                .map(r -> String.format("docId=%s, coarseScore=%.4f (bm25=%.2f, vector=%.2f)",
                        r.getDocId(), r.getCoarseRankScore(),
                        r.getBm25Score() != null ? r.getBm25Score() : 0,
                        r.getVectorScore() != null ? r.getVectorScore() : 0))
                .collect(Collectors.joining("; "));
        log.info("粗排Top{}结果: {}", topK, scoresLog);

        return ranked;
    }

    // ==================== Step 4: 精排 ====================

    /**
     * 精排: 使用重排序模型 (Qwen-rerank / BGE-Reranker / Cross-encoder)
     *
     * @param query   查询文本
     * @param results 粗排后的结果
     * @param topK    返回数量
     * @return 精排后的结果
     */
    private List<SearchResult> fineRanking(String query, List<SearchResult> results, int topK) {
        log.info("执行精排, provider={}, topK={}", rerankProvider, topK);

        if (results.isEmpty()) {
            return results;
        }

        // 限流保护：tryAcquire() 非阻塞，超限时降级
        if (!rerankRateLimiter.tryAcquire()) {
            log.warn("Rerank API 限流触发，降级返回粗排 Top{} 结果", topK);
            return results.stream().limit(topK).collect(Collectors.toList());
        }

        try {
            // 提取文档内容列表
            List<String> documents = results.stream()
                    .map(r -> r.getSegment().text())
                    .collect(Collectors.toList());

            // 调用重排序API
            List<Double> rerankScores = callRerankAPI(query, documents);

            // 将重排序分数设置到结果中
            for (int i = 0; i < results.size() && i < rerankScores.size(); i++) {
                results.get(i).setFineRankScore(rerankScores.get(i));
            }

            // 按精排分数降序排序
            List<SearchResult> ranked = results.stream()
                    .sorted(Comparator.comparing(SearchResult::getFineRankScore,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(topK)
                    .collect(Collectors.toList());

            // 打印精排结果
            String scoresLog = ranked.stream()
                    .map(r -> String.format("docId=%s, fineScore=%.4f",
                            r.getDocId(), r.getFineRankScore() != null ? r.getFineRankScore() : 0))
                    .collect(Collectors.joining("; "));
            log.info("精排Top{}结果: {}", topK, scoresLog);

            return ranked;

        } catch (Exception e) {
            log.error("精排失败，返回粗排结果", e);
            // 如果精排失败，返回粗排的前topK个
            return results.stream().limit(topK).collect(Collectors.toList());
        }
    }

    /**
     * 调用重排序API
     * 支持: DashScope (Qwen-rerank), BGE-Reranker, Cross-encoder
     */
    private List<Double> callRerankAPI(String query, List<String> documents) throws Exception {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        switch (rerankProvider.toLowerCase()) {
            case "dashscope":
                return callDashScopeRerank(query, documents);
            case "bge":
                return callBGEReranker(query, documents);
            case "cross-encoder":
                return callCrossEncoder(query, documents);
            default:
                log.warn("未知的重排序提供商: {}, 使用默认分数", rerankProvider);
                return documents.stream().map(d -> 0.5).collect(Collectors.toList());
        }
    }

    /**
     * 调用DashScope Qwen-rerank API
     */
    private List<Double> callDashScopeRerank(String query, List<String> documents) throws Exception {
        log.info("调用DashScope Qwen-rerank API");

        if (rerankApiKey == null || rerankApiKey.isBlank()) {
            throw new IllegalStateException("rag.rerank.api-key 未配置，无法调用 DashScope Rerank API");
        }

        String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + rerankApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gte-rerank-v2");

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", false);
        parameters.put("top_n", rerankTopN);
        requestBody.put("parameters", parameters);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序API调用失败: " + response.getStatusCode());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("output")) {
            throw new RuntimeException("API响应格式异常");
        }

        Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
        List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) output.get("results");

        // 按原始顺序返回分数
        Map<Integer, Double> indexToScore = new HashMap<>();
        for (Map<String, Object> item : rerankedResults) {
            Integer index = (Integer) item.get("index");
            Double score = ((Number) item.get("relevance_score")).doubleValue();
            indexToScore.put(index, score);
        }

        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            scores.add(indexToScore.getOrDefault(i, 0.0));
        }

        log.info("DashScope重排序完成，返回{}个分数", scores.size());
        return scores;
    }

    /**
     * 调用BGE-Reranker (本地 ONNX 模型)
     */
    private List<Double> callBGEReranker(String query, List<String> documents) throws Exception {
        log.info("调用BGE-Reranker (本地ONNX模型)");
        
        // TODO: 实现 BGE-Reranker 本地调用
        // 需要添加依赖: langchain4j-community-rerank-bge 或专门的 rerank 模型
        // 目前 BGE 主要用作 Embedding，Rerank 需要使用专门的 Rerank 模型
        
        // 方案1: 使用 DashScope API (已实现)
        // 方案2: 使用本地部署的 BGE-Reranker 模型 (需要额外依赖和模型文件)
        //   - 需要下载 BGE-reranker-base 或 BGE-reranker-large 模型
        //   - 使用 ONNX Runtime 或 HuggingFace Transformers 加载模型
        //   - 计算 query-document 的相关性分数
        // 方案3: 使用 HuggingFace Inference API
        
        log.warn("BGE-Reranker 本地模型尚未配置，返回默认分数。建议配置 rag.rerank.provider=dashscope");
        return documents.stream().map(d -> 0.5).collect(Collectors.toList());
    }

    /**
     * 调用Cross-encoder (需要本地部署)
     *
     * Cross-encoder 实现思路：
     * 1. 使用预训练的 Cross-encoder 模型（如 sentence-transformers/ms-marco-MiniLM-L-6-v2）
     * 2. 将 query 和 document 拼接输入模型：[CLS] query [SEP] document [SEP]
     * 3. 模型输出相关性分数（0-1之间）
     * 4. 优点：精度高，能捕捉 query-document 的细粒度交互
     * 5. 缺点：速度慢，需要逐个计算每个 pair
     *
     * 实现步骤：
     * - 方案A: 使用 HuggingFace Transformers (Python) + Flask/FastAPI 封装为 HTTP 服务
     * - 方案B: 使用 DJL (Deep Java Library) 在 Java 中直接加载 PyTorch/TensorFlow 模型
     * - 方案C: 使用 ONNX Runtime 加载导出的 ONNX 模型
     *
     * 示例代码框架：
     * ```java
     * List<Double> scores = new ArrayList<>();
     * for (String doc : documents) {
     *     // 1. Tokenize query + document
     *     // 2. Run inference with Cross-encoder model
     *     // 3. Extract relevance score from output
     *     double score = crossEncoderModel.predict(query, doc);
     *     scores.add(score);
     * }
     * return scores;
     * ```
     */
    private List<Double> callCrossEncoder(String query, List<String> documents) throws Exception {
        log.info("调用Cross-encoder (TODO: 需实现)");
        
        // TODO: 实现 Cross-encoder 调用
        // 推荐使用方案A：部署独立的 Python 服务，Java 通过 HTTP 调用
        // 或者使用方案B/C：在 Java 中集成深度学习框架
        
        log.warn("Cross-encoder 尚未实现，返回默认分数。建议使用 DashScope Rerank API");
        return documents.stream().map(d -> 0.5).collect(Collectors.toList());
    }
}
