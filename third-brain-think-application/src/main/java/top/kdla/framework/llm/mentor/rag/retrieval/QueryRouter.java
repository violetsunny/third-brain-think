package top.kdla.framework.llm.mentor.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询路由器（Query Router）。
 *
 * <p>根据查询内容决定路由到哪个检索路径：
 * <ul>
 *   <li>{@link RouteType#VECTOR}  — 语义向量检索（适合开放性、概念性问题）</li>
 *   <li>{@link RouteType#KEYWORD} — 关键词/BM25 检索（适合精确名词、编号查询）</li>
 *   <li>{@link RouteType#HYBRID}  — 混合检索（默认，结合两者）</li>
 *   <li>{@link RouteType#NONE}    — 不需要检索（闲聊、纯计算等）</li>
 * </ul>
 *
 * <p>当前实现为基于关键词规则的轻量路由，可替换为 LLM 路由或分类模型。
 */
@Slf4j
@Component
public class QueryRouter {

    public enum RouteType {
        VECTOR, KEYWORD, HYBRID, NONE
    }

    // ─────────────────────────────────────────────────────────────
    // Routing
    // ─────────────────────────────────────────────────────────────

    /**
     * 判断查询应路由到哪个检索路径。
     */
    public RouteType route(String query) {
        if (query == null || query.isBlank()) return RouteType.NONE;

        String lower = query.toLowerCase();

        // 纯计算/闲聊 → 不需要检索
        if (isChitchat(lower)) {
            log.debug("QueryRouter: NONE for query='{}'", shorten(query));
            return RouteType.NONE;
        }

        // 精确查询（含编号、版本号、专有名词缩写）→ 关键词检索
        if (isExactQuery(lower)) {
            log.debug("QueryRouter: KEYWORD for query='{}'", shorten(query));
            return RouteType.KEYWORD;
        }

        // 语义/概念性查询 → 向量检索
        if (isSemanticQuery(lower)) {
            log.debug("QueryRouter: VECTOR for query='{}'", shorten(query));
            return RouteType.VECTOR;
        }

        // 默认混合检索
        log.debug("QueryRouter: HYBRID for query='{}'", shorten(query));
        return RouteType.HYBRID;
    }

    /**
     * 根据路由类型执行检索，返回关联文档。
     * 当前为 stub 实现，子类或实际服务注入后覆盖。
     */
    public List<Document> retrieve(String query, VectorStoreRetriever vectorRetriever) {
        RouteType type = route(query);
        return switch (type) {
            case NONE -> List.of();
            case VECTOR, HYBRID, KEYWORD -> vectorRetriever.retrieve(query);
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Rule helpers
    // ─────────────────────────────────────────────────────────────

    private boolean isChitchat(String lower) {
        return lower.matches(".*(你好|hi|hello|谢谢|感谢|再见|拜拜|你是谁|几点了|今天是).*")
                || lower.matches("\\d[+\\-*/^%]\\d.*");
    }

    private boolean isExactQuery(String lower) {
        // 含数字编号、版本号、缩写全大写词
        return lower.matches(".*\\b[A-Z]{2,10}\\b.*")
                || lower.matches(".*v?\\d+\\.\\d+.*")
                || lower.matches(".*[（(]\\d{4,}[)）].*");
    }

    private boolean isSemanticQuery(String lower) {
        return lower.contains("是什么") || lower.contains("怎么") || lower.contains("为什么")
                || lower.contains("如何") || lower.contains("原理") || lower.contains("概念")
                || lower.contains("解释") || lower.contains("含义");
    }

    private String shorten(String q) {
        return q.length() > 30 ? q.substring(0, 30) + "..." : q;
    }

    /**
     * 向量检索策略接口（由调用方注入实现）。
     */
    @FunctionalInterface
    public interface VectorStoreRetriever {
        List<Document> retrieve(String query);
    }
}
