package cn.hollis.llm.mentor.ragdemo.agent.verification;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

/**
 * 主观评估器（LLM 驱动，语义判断）。
 *
 * <p>借鉴 gogo-agent SubjectiveAssessorAgent 的设计：
 * <ul>
 *   <li>单次 LLM 调用产出结构化评估结果</li>
 *   <li>失败时降级为 WARNING + "建议人工复核"，不静默吞错</li>
 *   <li>三种评估维度共用同一类，通过 {@link AssessmentType} 区分提示词</li>
 * </ul>
 */
@Slf4j
public class SubjectiveAssessor {

    private final ChatModel chatModel;
    private final AssessmentType type;
    private final BeanOutputConverter<AssessmentResult> converter =
            new BeanOutputConverter<>(AssessmentResult.class);

    public SubjectiveAssessor(ChatModel chatModel, AssessmentType type) {
        this.chatModel = chatModel;
        this.type = type;
    }

    /**
     * 执行主观评估。
     *
     * @param question         用户原始问题
     * @param answer           Agent 最终回答
     * @param retrievedContent 检索到的源文档内容
     * @return 评估结果
     */
    public VerificationResult assess(String question, String answer, String retrievedContent) {
        String promptText = buildPrompt(question, answer, retrievedContent);
        try {
            String raw = chatModel.call(new Prompt(List.of(
                    new SystemMessage(getSystemPrompt()),
                    new UserMessage(promptText)
            ))).getResult().getOutput().getText();

            AssessmentResult result = converter.convert(raw);
            if (result == null) {
                return fallback("LLM 返回结果解析失败");
            }

            VerificationSeverity severity = switch (result.verdict().toLowerCase()) {
                case "pass" -> VerificationSeverity.PASS;
                case "fail" -> VerificationSeverity.FAIL;
                default -> VerificationSeverity.WARNING;
            };

            return new VerificationResult(
                    type.name() + "Assessor",
                    severity,
                    result.summary(),
                    result.issues() != null ? result.issues() : List.of()
            );
        } catch (RuntimeException e) {
            log.error("主观评估[{}] 执行异常，降级为 WARNING", type, e);
            return fallback("评估器执行异常: " + e.getMessage());
        }
    }

    private VerificationResult fallback(String reason) {
        return VerificationResult.warning(
                type.name() + "Assessor",
                reason + "，建议人工复核",
                List.of("主观评估降级，无法自动判定" + type.name() + "维度")
        );
    }

    private String getSystemPrompt() {
        return switch (type) {
            case GROUNDEDNESS -> """
                    你是 RAG 答案事实依据评估专家。
                    判断【回答】中的信息是否都能在【检索到的源文档】中找到事实支撑。
                    如果回答包含源文档中无法找到的信息（幻觉），verdict 为 fail。
                    如果回答大部分有支撑但存在少量不确定信息，verdict 为 warning。
                    如果回答完全基于源文档，verdict 为 pass。
                    """;
            case RELEVANCE -> """
                    你是 RAG 答案相关性评估专家。
                    判断【回答】是否直接回应了【用户问题】的核心诉求。
                    如果回答与问题完全无关或答非所问，verdict 为 fail。
                    如果回答部分相关但遗漏了关键方面，verdict 为 warning。
                    如果回答精准命中问题核心，verdict 为 pass。
                    """;
            case COMPLETENESS -> """
                    你是 RAG 答案完整性评估专家。
                    判断【回答】是否完整覆盖了【用户问题】涉及的所有方面。
                    如果回答严重缺失关键信息导致无法解决问题，verdict 为 fail。
                    如果回答覆盖了主要方面但存在可补充的细节，verdict 为 warning。
                    如果回答全面完整，verdict 为 pass。
                    """;
        };
    }

    private String buildPrompt(String question, String answer, String retrievedContent) {
        return """
                ## 用户问题
                %s

                ## 检索到的源文档
                %s

                ## Agent 回答
                %s

                ## 输出format
                %s
                """.formatted(
                question,
                retrievedContent != null ? retrievedContent : "（无检索内容）",
                answer,
                converter.getFormat()
        );
    }

    /** LLM 返回的结构化评估结果 */
    public record AssessmentResult(
            @JsonProperty("verdict") String verdict,
            @JsonProperty("summary") String summary,
            @JsonProperty("issues") List<String> issues
    ) {}
}
