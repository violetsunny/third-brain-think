package cn.hollis.llm.mentor.ragdemo.agent.verification;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核结果收集器（Collecting Parameter 模式）。
 *
 * <p>借鉴 gogo-agent ReviewCollector 的设计，多个审核器将结果写入同一个收集器，
 * 仲裁层从此处读取汇总结果做最终判定。
 */
public class VerificationCollector {

    private final List<VerificationResult> results = new ArrayList<>();

    public void add(VerificationResult result) {
        results.add(result);
    }

    public List<VerificationResult> results() {
        return List.copyOf(results);
    }

    /** 是否存在任意 FAIL（硬约束违反） */
    public boolean hasAnyFail() {
        return results.stream().anyMatch(r -> r.severity() == VerificationSeverity.FAIL);
    }

    /** 是否存在任意 WARNING */
    public boolean hasAnyWarning() {
        return results.stream().anyMatch(r -> r.severity() == VerificationSeverity.WARNING);
    }

    /** 收集所有 issues */
    public List<String> allIssues() {
        return results.stream()
                .filter(r -> r.severity() != VerificationSeverity.PASS)
                .flatMap(r -> r.issues().stream())
                .toList();
    }
}
