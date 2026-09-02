package cn.hollis.llm.mentor.ragdemo.rerank;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 内容聚合器
 * 用于融合多路检索结果
 * 
 * 公式: RRF Score = Σ(1/(k + rank_i))
 * 其中 k 是常数(通常60)，rank_i 是文档在第i个检索结果中的排名
 */
@Slf4j
public class RRFContentAggregator implements ContentAggregator {

    private static final int DEFAULT_K = 60;
    
    private final Integer maxResults;
    private final Integer k;

    @Builder
    public RRFContentAggregator(Integer maxResults, Integer k) {
        this.maxResults = maxResults;
        this.k = k != null ? k : DEFAULT_K;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        log.debug("执行RRF融合，查询数量: {}", queryToContents.size());

        if (queryToContents.isEmpty()) {
            return Collections.emptyList();
        }

        // 存储每个内容的RRF分数
        Map<Content, Double> rrfScores = new HashMap<>();
        
        // 遍历每个查询的检索结果
        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            Query query = entry.getKey();
            Collection<List<Content>> contentsList = entry.getValue();
            
            // 合并所有检索列表
            for (List<Content> contents : contentsList) {
                log.debug("处理查询: {}, 检索结果数: {}", query.text(), contents.size());
                
                // 计算每个内容的RRF分数
                for (int i = 0; i < contents.size(); i++) {
                    Content content = contents.get(i);
                    int rank = i + 1;
                    double score = 1.0 / (k + rank);
                    
                    rrfScores.merge(content, score, Double::sum);
                }
            }
        }

        // 按RRF分数降序排序
        List<Content> aggregatedContents = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<Content, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 限制返回数量
        if (maxResults != null && maxResults > 0) {
            aggregatedContents = aggregatedContents.stream()
                    .limit(maxResults)
                    .collect(Collectors.toList());
        }

        log.debug("RRF融合完成，返回{}条结果", aggregatedContents.size());
        return aggregatedContents;
    }

    /**
     * 创建构建器
     */
    public static RRFContentAggregatorBuilder builder() {
        return new RRFContentAggregatorBuilder();
    }
}
