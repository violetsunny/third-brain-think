package cn.hollis.llm.mentor.ragdemo.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 检索结果封装类
 * 包含文档内容、各种分数和元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class SearchResult {
    
    /**
     * 文档ID
     */
    private String docId;
    
    /**
     * 文档内容
     */
    private TextSegment segment;
    
    /**
     * 向量相似度分数 (0-1)
     */
    private Double vectorScore;
    
    /**
     * BM25关键词匹配分数
     */
    private Double bm25Score;
    
    /**
     * 元数据匹配分数 (0-1)
     */
    private Double metadataScore;
    
    /**
     * RRF融合分数
     */
    private Double rrfScore;
    
    /**
     * 粗排综合分数
     */
    private Double coarseRankScore;
    
    /**
     * 精排重排序分数 (Cross-encoder/Qwen-rerank/BGE-Reranker)
     */
    private Double fineRankScore;
    
    /**
     * 来源: VECTOR, KEYWORD, METADATA
     */
    private String source;
    
    /**
     * 在原始列表中的排名
     */
    private Integer originalRank;
    
    /**
     * 计算粗排分数: BM25 + 向量相似度的加权组合
     */
    public double calculateCoarseScore(double bm25Weight, double vectorWeight) {
        double bm25 = this.bm25Score != null ? this.bm25Score : 0.0;
        double vector = this.vectorScore != null ? this.vectorScore : 0.0;
        this.coarseRankScore = bm25 * bm25Weight + vector * vectorWeight;
        return this.coarseRankScore;
    }
}
