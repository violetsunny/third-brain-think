package cn.hollis.llm.mentor.ragdemo.agent.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 答案多维验证服务 — 编排客观校验 + 主观评估 + 仲裁。
 *
 * <p>借鉴 gogo-agent 多维审核架构的设计：
 * <ol>
 *   <li><b>客观层</b>：{@link ObjectiveVerificationEngine} 执行确定性代码校验（引用存在性、数值一致性）</li>
 *   <li><b>主观层</b>：{@link SubjectiveAssessor} 三维 fanout 并行 LLM 评估（groundedness/relevance/completeness）</li>
 *   <li><b>仲裁层</b>：纯代码仲裁 — 客观 FAIL 一票否决，否则取最严重级别</li>
 * </ol>
 *
 * <p>硬约束一票否决：客观校验 FAIL（如引用造假）时，无论主观评估结果如何，整体判定为 FAIL。
 * 失败显性化：每个环节都有 fallback，降级时标记 WARNING 并提示人工复核。
 */
@Slf4j
@Service
public class AnswerVerificationService {

    private final ObjectiveVerificationEngine objectiveEngine;
    private final ChatModel chatModel;
    private final ExecutorService assessorPool;

    public AnswerVerificationService(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectiveEngine = new ObjectiveVerificationEngine();
        this.assessorPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 执行完整的多维答案验证。
     *
     * @param question         用户原始问题
     * @param answer           Agent 最终回答
     * @param retrievedContent 检索到的源文档内容（工具返回的原始文本）
     * @return 仲裁后的整体判定
     */
    public AnswerVerdict verify(String question, String answer, String retrievedContent) {
        VerificationCollector objectiveCollector = new VerificationCollector();
        VerificationCollector subjectiveCollector = new VerificationCollector();

        // 1. 客观校验（确定性代码，同步执行）
        objectiveEngine.runAll(answer, retrievedContent, objectiveCollector);

        // 2. 主观评估（LLM fanout，并行执行）
        List<CompletableFuture<VerificationResult>> assessorFutures = new ArrayList<>();
        for (AssessmentType type : AssessmentType.values()) {
            SubjectiveAssessor assessor = new SubjectiveAssessor(chatModel, type);
            assessorFutures.add(CompletableFuture.supplyAsync(
                    () -> assessor.assess(question, answer, retrievedContent),
                    assessorPool
            ));
        }

        for (CompletableFuture<VerificationResult> future : assessorFutures) {
            try {
                subjectiveCollector.add(future.join());
            } catch (CompletionException e) {
                log.error("主观评估 future 异常", e);
            }
        }

        // 3. 仲裁（纯代码，客观硬否决 + 最严重级别）
        return arbitrate(objectiveCollector, subjectiveCollector);
    }

    /**
     * 纯代码仲裁 — 不依赖 LLM。
     *
     * <p>规则（区分客观硬否决与主观 FAIL）：
     * <ul>
     *   <li>客观校验 FAIL → 整体 FAIL（硬否决），feedback 标注 [硬约束]</li>
     *   <li>客观无 FAIL 但主观有 FAIL → 整体 FAIL（非硬否决）</li>
     *   <li>无 FAIL 但有 WARNING → 整体 WARNING</li>
     *   <li>全部 PASS → 整体 PASS</li>
     * </ul>
     */
    private AnswerVerdict arbitrate(VerificationCollector objectiveCollector,
                                    VerificationCollector subjectiveCollector) {
        List<VerificationResult> allResults = new ArrayList<>();
        allResults.addAll(objectiveCollector.results());
        allResults.addAll(subjectiveCollector.results());

        // 客观 FAIL → 硬否决
        if (objectiveCollector.hasAnyFail()) {
            List<String> hardFailIssues = objectiveCollector.results().stream()
                    .filter(r -> r.severity() == VerificationSeverity.FAIL)
                    .flatMap(r -> r.issues().stream())
                    .map(issue -> "[硬约束] " + issue)
                    .toList();

            String feedback = "答案未通过硬约束校验，存在严重问题：\n" +
                    String.join("\n", hardFailIssues) +
                    "\n\n请重新基于检索到的源文档生成回答，确保所有引用真实存在。";

            log.warn("答案验证 FAIL（硬否决）: {}", feedback);
            return new AnswerVerdict(VerificationSeverity.FAIL, feedback, allResults);
        }

        // 主观 FAIL → FAIL（非硬否决）
        if (subjectiveCollector.hasAnyFail()) {
            List<String> subjectiveFailIssues = subjectiveCollector.results().stream()
                    .filter(r -> r.severity() == VerificationSeverity.FAIL)
                    .flatMap(r -> r.issues().stream())
                    .toList();

            String feedback = "答案未通过主观评估：\n" + String.join("\n", subjectiveFailIssues) +
                    "\n\n请根据以上意见重新生成回答。";

            log.warn("答案验证 FAIL（主观评估）: {}", feedback);
            return new AnswerVerdict(VerificationSeverity.FAIL, feedback, allResults);
        }

        // WARNING
        if (objectiveCollector.hasAnyWarning() || subjectiveCollector.hasAnyWarning()) {
            List<String> warningIssues = allResults.stream()
                    .filter(r -> r.severity() == VerificationSeverity.WARNING)
                    .flatMap(r -> r.issues().stream())
                    .toList();

            String feedback = "答案存在风险，建议改进：\n" + String.join("\n", warningIssues);
            log.info("答案验证 WARNING: {}", feedback);
            return new AnswerVerdict(VerificationSeverity.WARNING, feedback, allResults);
        }

        log.info("答案验证 PASS（全部校验通过）");
        return new AnswerVerdict(VerificationSeverity.PASS, null, allResults);
    }

    /**
     * 答案验证最终判定。
     *
     * @param severity 整体严重级别
     * @param feedback 改进反馈（PASS 时为 null）
     * @param details  所有审核器的详细结果
     */
    public record AnswerVerdict(
            VerificationSeverity severity,
            String feedback,
            List<VerificationResult> details
    ) {
        public boolean passed() {
            return severity == VerificationSeverity.PASS;
        }
    }
}
