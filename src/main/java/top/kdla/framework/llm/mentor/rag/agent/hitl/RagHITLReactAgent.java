package top.kdla.framework.llm.mentor.rag.agent.hitl;

import top.kdla.framework.llm.mentor.rag.agent.tool.RagToolService;
import top.kdla.framework.llm.mentor.rag.agent.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL（Human-In-The-Loop）ReAct Agent（RAG 版本）。
 *
 * <p>工作流：
 * <ol>
 *   <li>{@link #call(String, String)} 执行 ReAct 循环；遇到被 {@link HITLAdvisor} 拦截的工具调用时，
 *       返回 {@link AgentInterrupted}，携带待审批工具列表和当前消息快照。</li>
 *   <li>调用方审批后，通过 {@link #resume(AgentInterrupted, List)} 继续执行。</li>
 * </ol>
 * 同一工具名在会话内只需审批一次（由 {@link HITLState} 记录）。
 */
@Slf4j
@Component
public class RagHITLReactAgent {

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct 模式的智能 AI 助手，会通过 Reasoning → Act(ToolCall) → Observation 的反复循环来逐步解决任务。

            ## 工具调用规则（极其重要）
            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，且只通过工具调用字段输出。
            2. 工具调用时：禁止在 content 中出现任何形式的工具调用文本。
            3. 工具调用前后不得输出任何多余文字、标签、换行或说明。
            4. 工具参数必须是有效的 JSON，且尽量简洁。

            ## 最终答案规则
            1. 若已有完整信息，直接输出自然语言答案，禁止包含任何工具调用格式。
            2. 优先使用知识库内容作答；若知识库无相关内容，可结合通用知识回答。
            """;

    private static final int DEFAULT_MAX_ROUNDS = 8;

    private final ChatClient chatClient;
    private final List<ToolCallback> tools;

    /**
     * 默认构造：拦截所有 RAG 和天气工具（演示用）。
     * 实际使用时可通过 {@link #RagHITLReactAgent(ChatModel, RagToolService, WeatherTool, Set)} 自定义拦截集合。
     */
    public RagHITLReactAgent(ChatModel chatModel,
                              RagToolService ragToolService,
                              WeatherTool weatherTool) {
        this(chatModel, ragToolService, weatherTool,
                Set.of("searchKnowledgeBase", "getWeather"));
    }

    public RagHITLReactAgent(ChatModel chatModel,
                              RagToolService ragToolService,
                              WeatherTool weatherTool,
                              Set<String> interceptToolNames) {
        this.tools = Arrays.asList(ToolCallbacks.from(ragToolService, weatherTool));

        List<Advisor> advisors = new ArrayList<>();
        if (interceptToolNames != null && !interceptToolNames.isEmpty()) {
            advisors.add(new HITLAdvisor(interceptToolNames));
        }

        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(this.tools)
                .internalToolExecutionEnabled(false)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions)
                .defaultAdvisors(advisors)
                .defaultToolCallbacks(this.tools)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /** 开始执行。返回 {@link AgentFinished} 或 {@link AgentInterrupted}。 */
    public AgentResult call(String conversationId, String question) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(question));

        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put(HITLAdvisor.HITL_STATE_KEY, new HITLState());

        return run(messages, context);
    }

    /**
     * 恢复执行。在人工审批后调用。
     *
     * @param interrupted 中断时返回的快照
     * @param feedbacks   审批结果（每个 PendingToolCall 调用 {@code .approve()} 或 {@code .reject()} 后传入）
     */
    public AgentResult resume(AgentInterrupted interrupted, List<PendingToolCall> feedbacks) {
        List<Message> messages = new ArrayList<>(interrupted.checkpointMessages());
        Map<String, Object> context = interrupted.context();

        HITLState hitlState = (HITLState) context.get(HITLAdvisor.HITL_STATE_KEY);

        boolean hasNonInterceptExecuted = !messages.isEmpty()
                && messages.get(messages.size() - 1) instanceof ToolResponseMessage;

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (PendingToolCall fb : feedbacks) {
            if (hitlState.isConsumed(fb.id())) continue;
            hitlState.markConsumed(fb.id());
            if (fb.result() == PendingToolCall.FeedbackResult.APPROVED) {
                hitlState.markToolNameApproved(fb.name());
            }
            toolCalls.add(new AssistantMessage.ToolCall(fb.id(), "function", fb.name(), fb.arguments()));
        }

        if (!toolCalls.isEmpty() && !hasNonInterceptExecuted) {
            messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());
        }

        for (PendingToolCall fb : feedbacks) {
            if (!hitlState.isConsumed(fb.id())) continue;
            String result;
            if (fb.result() == PendingToolCall.FeedbackResult.REJECTED) {
                result = "用户不同意执行此工具，工具名称：" + fb.name() + "，工具描述：" + fb.description();
            } else if (fb.result() == PendingToolCall.FeedbackResult.EDIT) {
                // EDIT means the user modified the arguments — but the modified arguments are not yet
                // propagated back through this API (fb still carries the original arguments).
                // Log a warning and skip execution rather than silently running stale arguments.
                log.warn("HITL resume: FeedbackResult.EDIT is not yet supported; " +
                        "skipping tool execution for '{}'. Pass APPROVED with updated arguments instead.", fb.name());
                result = "{\"error\":\"EDIT feedback not supported; please re-submit with APPROVED and updated arguments\"}";
            } else {
                // APPROVED
                ToolCallback cb = findTool(fb.name());
                result = cb != null ? cb.call(fb.arguments()) : "{\"error\":\"工具未找到\"}";
            }
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(fb.id(), fb.name(), result)))
                    .build());
        }

        return run(messages, context);
    }

    // ─────────────────────────────────────────────────────────────
    // Core ReAct loop
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private AgentResult run(List<Message> messages, Map<String, Object> context) {
        int round = 0;
        while (true) {
            round++;
            if (round > DEFAULT_MAX_ROUNDS) {
                log.warn("Max rounds reached, forcing final answer");
                return new AgentFinished(chatClient.prompt().messages(messages).call().content());
            }

            ChatClientResponse response = chatClient.prompt()
                    .messages(messages)
                    .advisors(a -> context.forEach(a::param))
                    .call()
                    .chatClientResponse();

            // HITL interrupt
            if (Boolean.TRUE.equals(response.context().get(HITLAdvisor.HITL_REQUIRED))) {
                List<AssistantMessage.ToolCall> nonInterceptTools =
                        (List<AssistantMessage.ToolCall>) response.context().get(HITLAdvisor.HITL_NON_INTERCEPT_TOOLS);

                if (nonInterceptTools != null && !nonInterceptTools.isEmpty()) {
                    messages.add(AssistantMessage.builder()
                            .toolCalls(response.chatResponse().getResult().getOutput().getToolCalls())
                            .build());
                    for (AssistantMessage.ToolCall tc : nonInterceptTools) {
                        ToolCallback cb = findTool(tc.name());
                        String result = cb != null ? cb.call(tc.arguments()) : "{\"error\":\"工具未找到\"}";
                        messages.add(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)))
                                .build());
                    }
                }

                return new AgentInterrupted(
                        (List<PendingToolCall>) response.context().get(HITLAdvisor.HITL_PENDING_TOOLS),
                        List.copyOf(messages),
                        context
                );
            }

            // Final answer
            if (!response.chatResponse().hasToolCalls()) {
                return new AgentFinished(response.chatResponse().getResult().getOutput().getText());
            }

            // Execute tools
            AssistantMessage assistant = AssistantMessage.builder()
                    .toolCalls(response.chatResponse().getResult().getOutput().getToolCalls())
                    .build();
            messages.add(assistant);

            for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                ToolCallback cb = findTool(tc.name());
                String result = cb != null ? cb.call(tc.arguments()) : "{\"error\":\"工具未找到: " + tc.name() + "\"}";
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)))
                        .build());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElse(null);
    }
}
