package top.kdla.framework.llm.mentor.rag.agent.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CitationVerifier} and {@link NumericConsistencyVerifier}.
 *
 * <p>验证客观审核层的确定性逻辑：引用存在性校验和数值一致性校验。
 */
class ObjectiveVerifierTest {

    private CitationVerifier citationVerifier;
    private NumericConsistencyVerifier numericVerifier;

    @BeforeEach
    void setUp() {
        citationVerifier = new CitationVerifier();
        numericVerifier = new NumericConsistencyVerifier();
    }

    // ─────────────────────────────────────────────────────────────
    // CitationVerifier
    // ─────────────────────────────────────────────────────────────

    @Test
    void citation_allPresent_returnsPass() {
        String answer = "根据 [文档:doc-001] 的记录，产品保修期为1年。";
        String source = "[文档:doc-001]\n产品保修期为1年。";

        VerificationResult result = citationVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void citation_fabricatedDoc_returnsFail() {
        String answer = "根据 [文档:doc-fake] 的记录，产品保修期为1年。";
        String source = "[文档:doc-001]\n产品保修期为1年。";

        VerificationResult result = citationVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.FAIL);
        assertThat(result.issues()).anyMatch(i -> i.contains("doc-fake"));
    }

    @Test
    void citation_multipleCitations_allPresent_returnsPass() {
        String answer = "根据 [文档:doc-001] 和 [文档:doc-002]，保修期为1年。";
        String source = "[文档:doc-001]\n保修信息\n---\n[文档:doc-002]\n补充保修信息";

        VerificationResult result = citationVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);
    }

    @Test
    void citation_multipleCitations_oneFabricated_returnsFail() {
        String answer = "根据 [文档:doc-001] 和 [文档:doc-fake]，保修期为1年。";
        String source = "[文档:doc-001]\n保修信息";

        VerificationResult result = citationVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.FAIL);
        assertThat(result.issues()).hasSize(1);
    }

    @Test
    void citation_noCitationInAnswer_returnsWarning() {
        String answer = "产品保修期为1年。";
        String source = "[文档:doc-001]\n产品保修期为1年。";

        VerificationResult result = citationVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.WARNING);
    }

    // ─────────────────────────────────────────────────────────────
    // NumericConsistencyVerifier
    // ─────────────────────────────────────────────────────────────

    @Test
    void numeric_allMatch_returnsPass() {
        String answer = "电池容量为 5000mAh，续航时间为 48小时。";
        String source = "该产品电池容量 5000mAh，理论续航 48小时。";

        VerificationResult result = numericVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);
    }

    @Test
    void numeric_hallucinatedNumber_returnsWarning() {
        String answer = "电池容量为 99999mAh，续航时间为 48小时。";
        String source = "该产品电池容量 5000mAh，理论续航 48小时。";

        VerificationResult result = numericVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.WARNING);
        assertThat(result.issues()).anyMatch(i -> i.contains("99999"));
    }

    @Test
    void numeric_noNumbersInAnswer_returnsPass() {
        String answer = "产品支持快充和无线充电。";
        String source = "该产品电池容量 5000mAh。";

        VerificationResult result = numericVerifier.verify(answer, source);

        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);
    }

    @Test
    void numeric_emptyInput_returnsPass() {
        VerificationResult result = numericVerifier.verify("", "some content");
        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);

        result = numericVerifier.verify("some answer", "");
        assertThat(result.severity()).isEqualTo(VerificationSeverity.PASS);
    }

    // ─────────────────────────────────────────────────────────────
    // ObjectiveVerificationEngine
    // ─────────────────────────────────────────────────────────────

    @Test
    void engine_runsAllVerifiers_andCollectsResults() {
        String answer = "根据 [文档:doc-001]，电池容量 5000mAh。";
        String source = "[文档:doc-001]\n电池容量 5000mAh。";

        VerificationCollector collector = new VerificationCollector();
        ObjectiveVerificationEngine engine = new ObjectiveVerificationEngine();
        engine.runAll(answer, source, collector);

        assertThat(collector.results()).hasSize(2);
        assertThat(collector.hasAnyFail()).isFalse();
    }

    @Test
    void engine_fabricatedCitation_triggersFail() {
        String answer = "根据 [文档:doc-fake]，电池容量 99999mAh。";
        String source = "[文档:doc-001]\n电池容量 5000mAh。";

        VerificationCollector collector = new VerificationCollector();
        ObjectiveVerificationEngine engine = new ObjectiveVerificationEngine();
        engine.runAll(answer, source, collector);

        assertThat(collector.hasAnyFail()).isTrue();
        assertThat(collector.hasAnyWarning()).isTrue();
    }
}
