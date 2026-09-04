package top.kdla.framework.llm.mentor.rag.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 元数据检索器 - 基于元数据过滤
 * 根据文档的元数据信息进行筛选
 */
@Slf4j
public class MetadataContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final Predicate<TextSegment> metadataFilter;
    private final Integer maxResults;

    @Builder
    public MetadataContentRetriever(ContentRetriever delegate,
                                    Predicate<TextSegment> metadataFilter,
                                    Integer maxResults) {
        this.delegate = Objects.requireNonNull(delegate, "delegate不能为空");
        this.metadataFilter = Objects.requireNonNull(metadataFilter, "metadataFilter不能为空");
        this.maxResults = maxResults != null ? maxResults : 10;
    }

    @Override
    public List<Content> retrieve(Query query) {
        log.debug("执行元数据过滤检索: query={}", query.text());

        // 先使用委托的检索器获取结果
        List<Content> allContents = delegate.retrieve(query);
        log.debug("委托检索器返回 {} 个结果", allContents.size());

        // 应用元数据过滤器
        List<Content> filteredContents = allContents.stream()
                .filter(content -> {
                    TextSegment segment = content.textSegment();
                    return segment != null && metadataFilter.test(segment);
                })
                .limit(maxResults)
                .collect(Collectors.toList());

        log.debug("元数据过滤后剩余 {} 个结果", filteredContents.size());
        return filteredContents;
    }

    /**
     * 创建基于元数据键值对的过滤器
     * 
     * @param key 元数据键
     * @param value 期望的值
     * @return 元数据过滤器
     */
    public static Predicate<TextSegment> metadataEquals(String key, String value) {
        return segment -> {
            if (segment == null || segment.metadata() == null) {
                return false;
            }
            String metaValue = segment.metadata().getString(key);
            return value.equals(metaValue);
        };
    }

    /**
     * 创建基于元数据键存在的过滤器
     * 
     * @param key 元数据键
     * @return 元数据过滤器
     */
    public static Predicate<TextSegment> metadataExists(String key) {
        return segment -> {
            if (segment == null || segment.metadata() == null) {
                return false;
            }
            return segment.metadata().getString(key) != null;
        };
    }

    /**
     * 创建基于多个元数据条件的AND过滤器
     * 
     * @param filters 多个过滤器
     * @return 组合过滤器
     */
    @SafeVarargs
    public static Predicate<TextSegment> and(Predicate<TextSegment>... filters) {
        return Arrays.stream(filters)
                .reduce(Predicate::and)
                .orElse(t -> true);
    }

    /**
     * 创建基于多个元数据条件的OR过滤器
     * 
     * @param filters 多个过滤器
     * @return 组合过滤器
     */
    @SafeVarargs
    public static Predicate<TextSegment> or(Predicate<TextSegment>... filters) {
        return Arrays.stream(filters)
                .reduce(Predicate::or)
                .orElse(t -> false);
    }

    @Override
    public String toString() {
        return "MetadataContentRetriever{" +
                "delegate=" + delegate +
                ", maxResults=" + maxResults +
                '}';
    }
}
