package cn.hollis.llm.mentor.ragdemo.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 多数据源查询路由器（第二级路由）。
 *
 * <p>在第一级 {@link QueryRouter} 判定为 RAG 意图后，进一步判断应路由到哪个数据源：
 * <ul>
 *   <li>{@link SourceType#VECTOR}     — 向量检索（语义相似性、文档检索类问题）</li>
 *   <li>{@link SourceType#GRAPH}      — 图数据库检索（知识图谱、实体关联类问题，预留扩展）</li>
 *   <li>{@link SourceType#RELATIONAL} — 关系数据库检索（统计分析、结构化查询，预留扩展）</li>
 * </ul>
 *
 * <p>仅当 {@code rag.retrieval.enable-multi-source-routing=true} 时注册为 Bean；
 * 默认关闭，不影响最小启动配置。
 *
 * <p><strong>当前版本行为</strong>：路由结果仅写入日志，不改变实际检索路径——所有查询均走向量检索。
 * GRAPH / RELATIONAL 分支为预留扩展入口，分别对应未来的 Neo4j Graph RAG 和 Text-to-SQL 实现。
 *
 * <p>当前实现为基于关键词规则的轻量路由，可在后续版本替换为 LLM 路由或分类模型。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.retrieval.enable-multi-source-routing", havingValue = "true")
public class MultiSourceQueryRouter {

    public enum SourceType {
        /** 向量语义检索（默认路径） */
        VECTOR,
        /** 知识图谱检索（预留扩展，当前降级为 VECTOR） */
        GRAPH,
        /** 关系数据库 Text-to-SQL（预留扩展，当前降级为 VECTOR） */
        RELATIONAL
    }

    /**
     * 根据查询内容判断应路由到哪个数据源。
     *
     * @param query 用户查询
     * @return 数据源类型
     */
    public SourceType route(String query) {
        if (query == null || query.isBlank()) {
            return SourceType.VECTOR;
        }

        String lower = query.toLowerCase();

        // 图数据库信号：实体关联、关系推断类问题
        if (isGraphQuery(lower)) {
            log.debug("MultiSourceQueryRouter: GRAPH for query='{}'", shorten(query));
            return SourceType.GRAPH;
        }

        // 关系数据库信号：统计、聚合、结构化查询类问题
        if (isRelationalQuery(lower)) {
            log.debug("MultiSourceQueryRouter: RELATIONAL for query='{}'", shorten(query));
            return SourceType.RELATIONAL;
        }

        // 默认：向量检索
        log.debug("MultiSourceQueryRouter: VECTOR for query='{}'", shorten(query));
        return SourceType.VECTOR;
    }

    // ─────────────────────────────────────────────────────────────
    // Rule helpers
    // ─────────────────────────────────────────────────────────────

    private boolean isGraphQuery(String lower) {
        return lower.matches(".*(关系|关联|之间|上下级|父子|依赖|连接|链路|图谱|知识图|实体|节点|路径|影响|传导).*")
                || lower.matches(".*(和.*之间|与.*关系|.*的上游|.*的下游).*");
    }

    private boolean isRelationalQuery(String lower) {
        return lower.matches(".*(统计|数量|几个|几条|合计|总数|平均|最大|最小|汇总|排名|占比|比例|百分比).*")
                || lower.matches(".*(多少.*[条个件]|[条个件].*多少|列出所有|查询所有).*");
    }

    private String shorten(String q) {
        return q.length() > 30 ? q.substring(0, 30) + "..." : q;
    }
}
