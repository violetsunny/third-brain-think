package top.kdla.framework.llm.mentor.rag.agent.verification;

import java.util.List;

/**
 * 单个审核器的校验结果（不可变 record）。
 *
 * @param verifierName 审核器名称（如 "CitationVerifier"）
 * @param severity     严重级别
 * @param summary      一句话摘要
 * @param issues       具体问题列表（PASS 时为空）
 */
public record VerificationResult(
        String verifierName,
        VerificationSeverity severity,
        String summary,
        List<String> issues
) {
    /** 快速构造 PASS 结果 */
    public static VerificationResult pass(String verifierName, String summary) {
        return new VerificationResult(verifierName, VerificationSeverity.PASS, summary, List.of());
    }

    /** 快速构造 WARNING 结果 */
    public static VerificationResult warning(String verifierName, String summary, List<String> issues) {
        return new VerificationResult(verifierName, VerificationSeverity.WARNING, summary, issues);
    }

    /** 快速构造 FAIL 结果 */
    public static VerificationResult fail(String verifierName, String summary, List<String> issues) {
        return new VerificationResult(verifierName, VerificationSeverity.FAIL, summary, issues);
    }
}
