package cn.hollis.llm.mentor.ragdemo.agent.verification;

/**
 * 审核严重级别
 *
 * <p>借鉴 gogo-agent 多维审核架构的 CheckResult 分级：
 * <ul>
 *   <li>{@link #PASS} — 校验通过</li>
 *   <li>{@link #WARNING} — 存在风险但不阻断（如数值可能不匹配）</li>
 *   <li>{@link #FAIL} — 硬约束违反，一票否决（如引用造假）</li>
 * </ul>
 */
public enum VerificationSeverity {
    PASS,
    WARNING,
    FAIL
}
