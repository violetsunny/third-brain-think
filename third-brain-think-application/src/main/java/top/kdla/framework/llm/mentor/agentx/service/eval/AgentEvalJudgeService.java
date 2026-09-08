package top.kdla.framework.llm.mentor.agentx.service.eval;

import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentEvalJudgeOutput;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentEvalJudgeRequest;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentEvalJudgeResponse;
import top.kdla.framework.llm.mentor.agentx.prompt.AgentEvalPrompts;
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

/**
 * LLM-as-Judge 判分服务：裸大模型按 rubric 打四维度档位分，
 * 加权总分与通过判定由程序计算（正确性 ≥ 3 记通过）。
 */
@Slf4j
@Service
public class AgentEvalJudgeService {

    private static final int PASS_THRESHOLD = 3;
    private static final int ANSWER_MAX_LENGTH = 8000;

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.deepseek.base-url}")
    private String baseUrl;
    @Value("${spring.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${data-agent.agent-eval.judge-model:deepseek-v4-flash}")
    private String judgeModel;
    private ChatModel chatModel;

    public AgentEvalJudgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(Duration.ofSeconds(120));
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();
        this.chatModel = DeepSeekV4ChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(judgeModel)
                        .temperature(0.0)
                        .build())
                .build();
        log.info("[agent-eval] judge initialized | model={}", judgeModel);
    }

    public AgentEvalJudgeResponse judge(AgentEvalJudgeRequest req) {
        long start = System.currentTimeMillis();
        if (req == null || isBlank(req.question()) || isBlank(req.goldAnswer()) || isBlank(req.agentAnswer())) {
            return fail("question/goldAnswer/agentAnswer 不能为空", start);
        }
        try {
            BeanOutputConverter<AgentEvalJudgeOutput> converter =
                    new BeanOutputConverter<>(AgentEvalJudgeOutput.class, objectMapper);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(AgentEvalPrompts.JUDGE_INSTRUCTIONS
                            + "\n\n## Structured output\n\n" + converter.getFormat()),
                    new UserMessage(AgentEvalPrompts.JUDGE_USER_TEMPLATE.formatted(
                            req.question(), req.goldAnswer(), truncate(req.agentAnswer())))
            ));
            String content;
            AgentEvalJudgeOutput out = null;
            for (int attempt = 1; attempt <= 2 && out == null; attempt++) {
                content = chatModel.call(prompt).getResult().getOutput().getText();
                AgentEvalJudgeOutput parsed = converter.convert(content);
                if (isComplete(parsed)) {
                    out = parsed;
                } else {
                    log.warn("[agent-eval] judge output incomplete (attempt {}/{}) | content={}",
                            attempt, 2, snippet(content, 300));
                }
            }
            if (out == null) {
                return fail("judge failed: 裁判维度分缺失（重试后仍不完整）", start);
            }

            int correctness = requireScore(out.getCorrectness());
            int completeness = requireScore(out.getCompleteness());
            int relevance = requireScore(out.getRelevance());
            int logic = requireScore(out.getLogic());
            double total = correctness * 0.4 + completeness * 0.2 + relevance * 0.2 + logic * 0.2;
            boolean pass = correctness >= PASS_THRESHOLD;
            log.info("[agent-eval] judge | question={}... pass={} scores=正确性{}/完整性{}/相关性{}/逻辑性{} "
                            + "totalScore={} reason={} durationMs={}",
                    snippet(req.question()), pass, correctness, completeness, relevance, logic,
                    round2(total), out.getReason(), System.currentTimeMillis() - start);
            return new AgentEvalJudgeResponse(true, pass, correctness, completeness,
                    relevance, logic, round2(total), text(out.getReason()), null,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[agent-eval] judge failed | question={}... err={}",
                    snippet(req.question()), e.getMessage());
            return fail("judge failed: " + e.getMessage(), start);
        }
    }

    private AgentEvalJudgeResponse fail(String error, long start) {
        return new AgentEvalJudgeResponse(false, false, 0, 0, 0, 0, 0.0, null,
                error, System.currentTimeMillis() - start);
    }

    /** 四个维度分齐全才算解析成功。 */
    private static boolean isComplete(AgentEvalJudgeOutput out) {
        return out != null && out.getCorrectness() != null && out.getCompleteness() != null
                && out.getRelevance() != null && out.getLogic() != null;
    }

    private static int requireScore(Integer score) {
        return Math.max(0, Math.min(5, score));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String truncate(String text) {
        return text.length() > ANSWER_MAX_LENGTH
                ? text.substring(0, ANSWER_MAX_LENGTH) + "\n...(报告过长已截断)"
                : text;
    }

    private static String snippet(String text) {
        return snippet(text, 30);
    }

    private static String snippet(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
