package top.kdla.framework.llm.mentor.rag.agent.verification;

/**
 * 客观审核器接口（确定性逻辑，不调用 LLM）。
 *
 * <p>借鉴 gogo-agent ObjectiveReviewer 设计：每个审核器负责单一维度，
 * 纯代码校验，结果写入 {@link VerificationCollector}。
 */
public interface ObjectiveVerifier {

    /**
     * 执行客观校验。
     *
     * @param answer          Agent 最终回答文本
     * @param retrievedContent 检索到的知识库内容（工具返回的原始文本）
     * @return 校验结果
     */
    VerificationResult verify(String answer, String retrievedContent);

    /** 审核器名称 */
    String name();
}
