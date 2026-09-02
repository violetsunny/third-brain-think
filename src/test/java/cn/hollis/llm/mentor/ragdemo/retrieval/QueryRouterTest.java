package cn.hollis.llm.mentor.ragdemo.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryRouter}.
 */
class QueryRouterTest {

    private QueryRouter router;

    @BeforeEach
    void setUp() {
        router = new QueryRouter();
    }

    /**
     * Null / blank query should route to NONE (no retrieval needed).
     */
    @Test
    void nullOrBlankQuery_routesToNone() {
        assertThat(router.route(null)).isEqualTo(QueryRouter.RouteType.NONE);
        assertThat(router.route("")).isEqualTo(QueryRouter.RouteType.NONE);
        assertThat(router.route("   ")).isEqualTo(QueryRouter.RouteType.NONE);
    }

    /**
     * Chitchat greetings should route to NONE.
     */
    @Test
    void chitchatQuery_routesToNone() {
        assertThat(router.route("你好")).isEqualTo(QueryRouter.RouteType.NONE);
        assertThat(router.route("hello")).isEqualTo(QueryRouter.RouteType.NONE);
        assertThat(router.route("谢谢你的帮助")).isEqualTo(QueryRouter.RouteType.NONE);
    }

    /**
     * Conceptual / explanatory questions should route to VECTOR.
     */
    @Test
    void semanticQuery_routesToVector() {
        assertThat(router.route("RAG是什么")).isEqualTo(QueryRouter.RouteType.VECTOR);
        assertThat(router.route("如何理解向量数据库")).isEqualTo(QueryRouter.RouteType.VECTOR);
        assertThat(router.route("解释一下检索增强生成的原理")).isEqualTo(QueryRouter.RouteType.VECTOR);
    }

    /**
     * General knowledge queries without specific semantic keywords default to HYBRID.
     */
    @Test
    void generalQuery_routesToHybrid() {
        assertThat(router.route("Spring Boot 配置中心最佳实践")).isEqualTo(QueryRouter.RouteType.HYBRID);
        assertThat(router.route("知识库管理")).isEqualTo(QueryRouter.RouteType.HYBRID);
    }
}
