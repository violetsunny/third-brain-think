package top.kdla.framework.llm.mentor.rag.agent.advisor;

import top.kdla.framework.llm.mentor.rag.agent.verification.AnswerVerificationService;
import top.kdla.framework.llm.mentor.rag.agent.verification.AnswerVerificationService.AnswerVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.stream.Collectors;

/**
 * 多维验证 Advisor — 替换原 ReflectionAdvisor 的增强版。
 *
 * <p>借鉴 gogo-agent 多维审核架构，在 Agent 给出非工具调用回答后执行：
 * <ol>
 *   <li>从对话历史中提取检索到的源文档内容（ToolResponseMessage）</li>
 *   <li>调用 {@link AnswerVerificationService} 做客观校验 + 主观评估 + 仲裁</li>
 *   <li>验证通过 → 直接返回回答</li>
 *   <li>验证未通过 → 注入 {@code verification.required=true} 和 {@code verification.feedback}，
 *       驱动 Agent 重新规划（与原 ReflectionAdvisor 的 context key 模式一致）</li>
 * </ol>
 *
 * <p>硬否决：客观校验 FAIL（如引用造假）时，强制要求 Agent 重新作答。
 * 失败显性化：所有降级路径都在 feedback 中明确标注。
 */
@Slf4j
public class VerificationAdvisor implements CallAdvisor {

    private final AnswerVerificationService verificationService;

    public VerificationAdvisor(AnswerVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
            return response;
        }

        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return response;
        }

        String answer = response.chatResponse().getResult().getOutput().getText();
        String question = extractQuestion(request);
        String retrievedContent = extractRetrievedContent(request);

        if (retrievedContent.isBlank()) {
            log.debug("无检索内容，跳过多维验证");
            return response;
        }

        AnswerVerdict verdict = verificationService.verify(question, answer, retrievedContent);

        if (verdict.passed()) {
            log.debug("======= 多维验证通过 =======");
            return response;
        }

        log.info("======= 多维验证未通过[{}]，需要 Agent 重新执行 =======", verdict.severity());

        return response.mutate()
                .context("verification.required", true)
                .context("verification.feedback", verdict.feedback())
                .context("verification.severity", verdict.severity().name())
                .build();
    }

    @Override
    public String getName() {
        return "VerificationAdvisor";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    /**
     * 从对话历史中提取所有 ToolResponseMessage 的内容，作为检索到的源文档。
     */
    private String extractRetrievedContent(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(trm -> trm.getResponses().stream())
                .map(tr -> tr.responseData())
                .filter(data -> data != null && !data.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String extractQuestion(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .reduce((first, second) -> second)
                .orElse("");
    }
}
