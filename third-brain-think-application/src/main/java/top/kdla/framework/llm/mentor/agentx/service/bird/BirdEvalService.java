package top.kdla.framework.llm.mentor.agentx.service.bird;

import top.kdla.framework.llm.mentor.agentx.domain.dto.BirdAgentOutput;
import top.kdla.framework.llm.mentor.agentx.domain.dto.BirdEvalRequest;
import top.kdla.framework.llm.mentor.agentx.domain.dto.BirdEvalResponse;
import top.kdla.framework.llm.mentor.agentx.prompt.BirdEvalPrompts;
import top.kdla.framework.llm.mentor.agentx.service.bird.BirdEvalToolFactory.BirdToolBundle;
import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import com.agentx.ai.core.model.RunnableParams;
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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * BIRD single-question evaluation service.
 *
 * <p>This service builds a separate ReactAgent for the BIRD text-to-SQL task.
 * It must not share the production data-agent's SQL validation or permission
 * pipeline because BIRD compares the submitted SQL directly through its own
 * execution-result metric.</p>
 */
@Slf4j
@Service
public class BirdEvalService {

    private static final int DEFAULT_MAX_ROUNDS = 50;

    private final BirdEvalToolFactory toolFactory;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.deepseek.base-url}")
    private String baseUrl;
    @Value("${spring.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-pro}")
    private String model;
    @Value("${data-agent.bird-eval.temperature:0.0}")
    private double temperature;
    @Value("${data-agent.bird-eval.max-rounds:" + DEFAULT_MAX_ROUNDS + "}")
    private int defaultMaxRounds;
    private ChatModel chatModel;

    public BirdEvalService(BirdEvalToolFactory toolFactory, ObjectMapper objectMapper) {
        this.toolFactory = toolFactory;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        this.chatModel = buildChatModel();
        log.info("[bird-eval] initialized | model={}", model);
    }

    public BirdEvalResponse evalQuestion(BirdEvalRequest req) {
        long start = System.currentTimeMillis();
        BirdEvalResponse invalid = validateRequest(req, start);
        if (invalid != null) {
            return invalid;
        }
        String questionId = text(req.questionId());
        String dbId = text(req.dbId());

        try {
            RunnableParams params = RunnableParams.builder()
                    .outputType(BirdAgentOutput.class)
                    .build();
            BirdAgentOutput output = callAgent(req, buildQuery(req), params);
            String sql = output.getSql() == null ? "" : output.getSql().trim();
            if (sql.isBlank()) {
                return fail(questionId, dbId, "agent returned blank SQL", start);
            }
            return new BirdEvalResponse(true, sql, null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[bird-eval] agent execution failed | questionId={} err={}",
                    questionId, e.getMessage(), e);
            return fail(questionId, dbId, "agent execution failed: " + e.getMessage(), start);
        }
    }

    public BirdEvalResponse evalBaseline(BirdEvalRequest req) {
        long start = System.currentTimeMillis();
        BirdEvalResponse invalid = validateRequest(req, start);
        if (invalid != null) {
            return invalid;
        }
        String questionId = text(req.questionId());
        String dbId = text(req.dbId());

        try {
            BeanOutputConverter<BirdAgentOutput> outputConverter =
                    new BeanOutputConverter<>(BirdAgentOutput.class, objectMapper);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(buildBaselineInstructions(req, outputConverter.getFormat())),
                    new UserMessage(buildQuery(req))
            ));
            String content = chatModel.call(prompt).getResult().getOutput().getText();
            BirdAgentOutput output = outputConverter.convert(content);
            String sql = output.getSql() == null ? "" : output.getSql().trim();
            if (sql.isBlank()) {
                return fail(questionId, dbId, "baseline returned blank SQL", start);
            }
            return new BirdEvalResponse(true, sql, null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[bird-eval] baseline execution failed | questionId={} err={}",
                    questionId, e.getMessage(), e);
            return fail(questionId, dbId, "baseline execution failed: " + e.getMessage(), start);
        }
    }

    private ReactAgent buildAgent(BirdEvalRequest req) {
        BirdToolBundle bundle = toolFactory.create(req.sqlitePath(), buildReviewContext(req));
        ToolCallback[] tools = mergeTools(
                ToolCallbacks.from(bundle.listTablesTool()),
                ToolCallbacks.from(bundle.describeTablesTool()),
                ToolCallbacks.from(bundle.verifySqlTool())
        );

        return ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(buildInstructions(req))
                .tools(tools)
                .maxRounds(resolveMaxRounds(req))
                .build();
    }

    private BirdAgentOutput callAgent(BirdEvalRequest req, String query, RunnableParams params)
            throws Exception {
        String json = buildAgent(req).call(query, params);
        return objectMapper.readValue(json, BirdAgentOutput.class);
    }

    private int resolveMaxRounds(BirdEvalRequest req) {
        return req.maxRounds() == null || req.maxRounds() <= 0
                ? defaultMaxRounds
                : req.maxRounds();
    }

    private BirdEvalResponse validateRequest(BirdEvalRequest req, long start) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            return fail(text(req == null ? null : req.questionId()),
                    text(req == null ? null : req.dbId()), "question is blank", start);
        }
        if (req.sqlitePath() == null || req.sqlitePath().isBlank()) {
            return fail(text(req.questionId()), text(req.dbId()), "sqlitePath is blank", start);
        }
        return null;
    }

    private BirdEvalResponse fail(String questionId, String dbId, String error, long start) {
        log.warn("[bird-eval] questionId={} dbId={} failed: {}", questionId, dbId, error);
        return new BirdEvalResponse(false, "", error, System.currentTimeMillis() - start);
    }

    private String buildInstructions(BirdEvalRequest req) {
        return BirdEvalPrompts.COMMON_INSTRUCTIONS
                + "\n\n" + BirdEvalPrompts.AGENT_INSTRUCTIONS
                + "\n\n## Current database\n\nDatabase ID: "
                + text(req.dbId());
    }

    private String buildBaselineInstructions(BirdEvalRequest req, String structuredOutputFormat) {
        return BirdEvalPrompts.COMMON_INSTRUCTIONS
                + "\n\n" + BirdEvalPrompts.BASELINE_INSTRUCTIONS
                + "\n\n## Current database\n\nDatabase ID: "
                + text(req.dbId())
                + "\n\n## Complete schema\n\n"
                + toolFactory.describeAllTables(req.sqlitePath())
                + "\n\n## Structured output\n\n"
                + structuredOutputFormat;
    }

    private String buildReviewContext(BirdEvalRequest req) {
        return "Question: " + req.question() + "\nEvidence: "
                + (req.evidence() == null || req.evidence().isBlank() ? "(none)" : req.evidence());
    }

    private String buildQuery(BirdEvalRequest req) {
        String evidence = req.evidence() == null || req.evidence().isBlank()
                ? "(none)"
                : req.evidence();
        return """
                Generate one SQLite SELECT/WITH query for the following BIRD question.
                
                Question:
                %s
                
                Evidence:
                %s
                """.formatted(req.question(), evidence);
    }

    private ChatModel buildChatModel() {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(Duration.ofSeconds(300));
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();
        return DeepSeekV4ChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .build();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
