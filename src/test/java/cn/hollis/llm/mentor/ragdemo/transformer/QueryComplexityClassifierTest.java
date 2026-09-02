package cn.hollis.llm.mentor.ragdemo.transformer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryComplexityClassifier}.
 *
 * <p>验证 L1 规则短路的判定准确性：简单查询判定为 SIMPLE（跳过改写），
 * 含指代词/追问/多子问题/分析关键词的查询判定为 COMPLEX（走改写管道）。
 */
class QueryComplexityClassifierTest {

    private QueryComplexityClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new QueryComplexityClassifier();
    }

    // ─────────────────────────────────────────────────────────────
    // SIMPLE 查询 — 无指代、单意图、实体明确
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "iPhone 15电池续航时间",
            "笔记本电脑价格指南",
            "电脑运行卡顿故障排查",
            "产品保修期查询",
            "Spring Boot自动配置原理",
            "MySQL索引优化方法"
    })
    void clearEntityQuery_classifiedAsSimple(String query) {
        assertThat(classifier.classify(query))
                .as("无指代词的清晰实体查询应判定为 SIMPLE: %s", query)
                .isEqualTo(QueryComplexity.SIMPLE);
    }

    @Test
    void shortButMeaningfulQuery_classifiedAsSimple() {
        assertThat(classifier.classify("退款流程")).isEqualTo(QueryComplexity.SIMPLE);
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLEX — 含指代词
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "它的电池续航怎么样",
            "这个产品有什么功能",
            "那个文档在哪里",
            "其配置参数是多少",
            "该方法有什么限制"
    })
    void queryWithPronoun_classifiedAsComplex(String query) {
        assertThat(classifier.classify(query))
                .as("含指代词的查询应判定为 COMPLEX: %s", query)
                .isEqualTo(QueryComplexity.COMPLEX);
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLEX — 模糊追问
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "继续",
            "然后呢",
            "还有呢",
            "接着说"
    })
    void followUpQuery_classifiedAsComplex(String query) {
        assertThat(classifier.classify(query))
                .as("模糊追问应判定为 COMPLEX: %s", query)
                .isEqualTo(QueryComplexity.COMPLEX);
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLEX — 多子问题
    // ─────────────────────────────────────────────────────────────

    @Test
    void multiQuestionQuery_classifiedAsComplex() {
        assertThat(classifier.classify("iPhone 15续航多少，价格是多少呢"))
                .isEqualTo(QueryComplexity.COMPLEX);
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLEX — 分析/对比关键词
    // ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "MySQL和PostgreSQL对比",
            "Spring Boot和Quarkus有什么区别",
            "分析微服务架构的优缺点"
    })
    void analysisQuery_classifiedAsComplex(String query) {
        assertThat(classifier.classify(query))
                .as("分析/对比类查询应判定为 COMPLEX: %s", query)
                .isEqualTo(QueryComplexity.COMPLEX);
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLEX — 边界情况
    // ─────────────────────────────────────────────────────────────

    @Test
    void nullQuery_classifiedAsComplex() {
        assertThat(classifier.classify(null)).isEqualTo(QueryComplexity.COMPLEX);
    }

    @Test
    void blankQuery_classifiedAsComplex() {
        assertThat(classifier.classify("  ")).isEqualTo(QueryComplexity.COMPLEX);
    }

    @Test
    void tooShortQuery_classifiedAsComplex() {
        assertThat(classifier.classify("ab")).isEqualTo(QueryComplexity.COMPLEX);
    }

    @Test
    void tooLongQuery_classifiedAsComplex() {
        String longQuery = "请详细分析".repeat(25);
        assertThat(classifier.classify(longQuery)).isEqualTo(QueryComplexity.COMPLEX);
    }
}
