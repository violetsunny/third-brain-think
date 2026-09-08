package top.kdla.framework.llm.mentor.agentx.service.eval;

import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentDiagnoseOutput;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentDiagnoseRequest;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentDiagnoseResponse;
import top.kdla.framework.llm.mentor.agentx.prompt.AgentDiagnosePrompts;
import top.kdla.framework.llm.mentor.agentx.tools.eval.TraceQueryTools;
import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.chatmodels.DeepSeekV4ChatModel;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 问题诊断服务：判错之后起一个带 trace 工具的 ReactAgent 定位问题。
 *
 * <p>与裁判（AgentEvalJudgeService，裸大模型只比对结果）互补：诊断 Agent 关注执行过程，
 * 注入三个 trace 查询工具（全貌/单轮/首现），找到第一次出错的轮次并归因到
 * 模型能力/提示词/工具/框架四类。结构化输出用框架原生 RunnableParams.outputType。
 *
 * <p>诊断 Agent 自身不落库（enableSession=false）、不写 trace（enableTrace=false），
 * 避免污染被测的评测记录。
 */
@Slf4j
@Service
public class AgentDiagnoseService {

    private static final int MAX_ROUNDS = 50;

    private final ObjectMapper objectMapper;
    private final TraceQueryTools traceQueryTools;

    @Value("${spring.ai.deepseek.base-url}")
    private String baseUrl;
    @Value("${spring.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${spring.ai.deepseek.chat.options.model}")
    private String model;

    private ReactAgent diagnoseAgent;

    public AgentDiagnoseService(ObjectMapper objectMapper,
                                TraceQueryTools traceQueryTools) {
        this.objectMapper = objectMapper;
        this.traceQueryTools = traceQueryTools;
    }

    @PostConstruct
    void init() {
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
        ChatModel chatModel = DeepSeekV4ChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(model)
                        .temperature(0.0)  // 诊断结论要求可复现，与裁判同温度
                        .build())
                .build();

        String instructions = AgentDiagnosePrompts.DIAGNOSE_INSTRUCTIONS;
        // 单例构建：工具与指令固定，call() 每次创建独立执行器，无共享状态
        this.diagnoseAgent = ReactAgent.builder()
                .name("trace-diagnose")
                .description("评测问题诊断：沿 trace 定位根源错误点并归因")
                .chatModel(chatModel)
                .instructions(instructions)
                .tools(ToolCallbacks.from(traceQueryTools))
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .maxRounds(MAX_ROUNDS)
                .enableSession(false)
                .enableTrace(false)
                .build();
        log.info("[agent-eval] diagnose agent initialized | model={} | maxRounds={}", model, MAX_ROUNDS);
    }

    public AgentDiagnoseResponse diagnose(AgentDiagnoseRequest req) {
        long start = System.currentTimeMillis();
        if (req == null || isBlank(req.conversationId())) {
            return fail(null, "conversationId 不能为空", start);
        }
        try {
            String query = AgentDiagnosePrompts.DIAGNOSE_USER_TEMPLATE.formatted(
                    req.conversationId(),
                    nz(req.question()), nz(req.goldAnswer()),
                    nz(req.agentAnswer()), nz(req.judgeReason()));

            // 结构化输出走框架原生 outputType（同 BirdEvalService.evalQuestion）：
            // 框架注入 schema 约束，返回 JSON 字符串后用 ObjectMapper 解析
            RunnableParams params = RunnableParams.builder()
                    .outputType(AgentDiagnoseOutput.class)
                    .build();
            String json = diagnoseAgent.call(query, params);
            AgentDiagnoseOutput out = objectMapper.readValue(json, AgentDiagnoseOutput.class);
            if (isBlank(out.getAttribution())) {
                return fail(req.conversationId(),
                        "诊断输出不完整（attribution 为空）：" + snippet(json), start);
            }
            log.info("[agent-eval] diagnose | conversationId={} root={}/{} attribution={} durationMs={}",
                    req.conversationId(), out.getRootSessionId(), out.getRootRound(),
                    out.getAttribution(), System.currentTimeMillis() - start);
            return new AgentDiagnoseResponse(true, req.conversationId(),
                    text(out.getRootSessionId()), out.getRootRound(),
                    text(out.getAttribution()), text(out.getEvidence()),
                    text(out.getAnalysis()), text(out.getSuggestion()),
                    null, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[agent-eval] diagnose failed | conversationId={} err={}",
                    req.conversationId(), e.getMessage(), e);
            return fail(req.conversationId(), "diagnose failed: " + e.getMessage(), start);
        }
    }

    private AgentDiagnoseResponse fail(String conversationId, String error, long start) {
        return new AgentDiagnoseResponse(false, conversationId, null, null,
                null, null, null, null, error, System.currentTimeMillis() - start);
    }

    private static String nz(String value) {
        return value == null ? "（未提供）" : value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
