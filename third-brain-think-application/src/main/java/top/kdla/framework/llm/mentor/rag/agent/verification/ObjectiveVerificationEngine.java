package top.kdla.framework.llm.mentor.rag.agent.verification;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 客观审核引擎 — 编排所有 {@link ObjectiveVerifier}，将结果写入 {@link VerificationCollector}。
 *
 * <p>借鉴 gogo-agent ObjectiveReviewEngine 的设计：引擎负责调度，
 * 每个审核器独立执行单一维度校验，互不依赖。
 */
@Slf4j
public class ObjectiveVerificationEngine {

    private final List<ObjectiveVerifier> verifiers;

    public ObjectiveVerificationEngine() {
        this.verifiers = List.of(
                new CitationVerifier(),
                new NumericConsistencyVerifier()
        );
    }

    /**
     * 执行所有客观校验。
     *
     * @param answer          Agent 最终回答
     * @param retrievedContent 检索到的源文档内容
     * @param collector       结果收集器
     */
    public void runAll(String answer, String retrievedContent, VerificationCollector collector) {
        for (ObjectiveVerifier verifier : verifiers) {
            try {
                VerificationResult result = verifier.verify(answer, retrievedContent);
                collector.add(result);
                log.info("客观校验[{}] => {}", verifier.name(), result.severity());
            } catch (RuntimeException e) {
                log.error("客观校验[{}] 执行异常，降级为 WARNING", verifier.name(), e);
                collector.add(VerificationResult.warning(
                        verifier.name(),
                        "校验器执行异常: " + e.getMessage(),
                        List.of("校验器内部错误，建议人工复核")
                ));
            }
        }
    }
}
