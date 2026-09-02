package top.kdla.framework.llm.mentor.rag.agent;

import top.kdla.framework.llm.mentor.rag.agent.prompts.AgentDefaultPrompts;
import top.kdla.framework.llm.mentor.rag.agent.tool.RagToolService;
import top.kdla.framework.llm.mentor.rag.agent.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Plan-Execute Agent（RAG 版本）。
 *
 * <p>执行流程：Generate Plan → Execute Tasks → Critique → Compress（可选） → Summarize。
 * 同一 order 的任务并行执行（Semaphore 控制最大并发 3），每个子任务由内嵌 ReAct 循环驱动。
 */
@Slf4j
@Component
public class RagPlanExecuteAgent {

    private static final int DEFAULT_MAX_ROUNDS = 5;
    private static final int DEFAULT_CONTEXT_CHAR_LIMIT = 50000;
    private static final int DEFAULT_MAX_TOOL_RETRIES = 2;

    private final ChatModel chatModel;
    private final List<ToolCallback> tools;
    private final Semaphore toolSemaphore = new Semaphore(3);

    public RagPlanExecuteAgent(ChatModel chatModel,
                               RagToolService ragToolService,
                               WeatherTool weatherTool) {
        this.chatModel = chatModel;
        this.tools = Arrays.asList(ToolCallbacks.from(ragToolService, weatherTool));
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    public String call(String conversationId, String question) {
        return execute(conversationId, question,
                DEFAULT_MAX_ROUNDS, DEFAULT_CONTEXT_CHAR_LIMIT, DEFAULT_MAX_TOOL_RETRIES);
    }

    // ─────────────────────────────────────────────────────────────
    // Core loop
    // ─────────────────────────────────────────────────────────────

    private String execute(String conversationId, String question,
                           int maxRounds, int contextCharLimit, int maxToolRetries) {
        OverAllState state = new OverAllState(conversationId, question);
        state.add(new UserMessage(question));

        int round = 0;
        while (round < maxRounds) {
            round++;
            log.info("===== Plan-Execute Round {} =====", round);

            List<PlanTask> plan = generatePlan(state, round);
            log.info("Plan: {}", plan);
            state.add(new AssistantMessage("【Execution Plan】\n" + plan));

            if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                log.info("No tools needed, going to summarize");
                break;
            }

            executePlan(plan, state, maxToolRetries);

            CritiqueResult critique = critique(state);
            if (critique.passed()) {
                log.info("Goal satisfied");
                break;
            }
            log.info("Goal not satisfied: {}", critique.feedback());
            state.add(new AssistantMessage("【Critique Feedback】\n" + critique.feedback()));

            compressIfNeeded(state, contextCharLimit);
        }

        if (round >= maxRounds) {
            log.info("Max rounds reached");
        }

        return summarize(state);
    }

    // ─────────────────────────────────────────────────────────────
    // Plan generation
    // ─────────────────────────────────────────────────────────────

    private List<PlanTask> generatePlan(OverAllState state, int round) {
        String toolDesc = renderToolDescriptions();
        BeanOutputConverter<List<PlanTask>> converter =
                new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
                });

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage("""
                当前时间是：%s。
                
                当前是迭代的第 %s 轮次。
                
                ## 可用工具说明（仅用于规划参考）
                %s
                
                ## 输出format
                %s
                
                """.formatted(
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
                round,
                toolDesc,
                converter.getFormat()
        ) + AgentDefaultPrompts.PLAN));
        promptMessages.add(new UserMessage("【对话历史】\n\n" + renderMessages(state.messages)));

        String json = chatModel.call(new Prompt(promptMessages)).getResult().getOutput().getText();
        return converter.convert(json);
    }

    // ─────────────────────────────────────────────────────────────
    // Plan execution
    // ─────────────────────────────────────────────────────────────

    private void executePlan(List<PlanTask> plan, OverAllState state, int maxToolRetries) {
        Map<Integer, List<PlanTask>> grouped =
                plan.stream().collect(Collectors.groupingBy(PlanTask::order));
        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();

        for (Integer order : new TreeSet<>(grouped.keySet())) {
            String dependencySnapshot = renderDependencySnapshot(accumulatedResults);
            List<PlanTask> tasks = grouped.get(order);

            List<CompletableFuture<Void>> futures = tasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {
                        try {
                            toolSemaphore.acquire();
                            if (task == null || !StringUtils.hasText(task.id())) return;

                            TaskResult result = executeTaskWithRetry(task, dependencySnapshot, maxToolRetries);
                            if (result.success() && result.output() != null) {
                                accumulatedResults.put(task.id(), result.output());
                            }
                            state.add(new AssistantMessage("""
                                    【Completed Task Result】
                                    taskId: %s
                                    success: %s
                                    result:
                                    %s
                                    error:
                                    %s
                                    【End Task Result】
                                    """.formatted(task.id(), result.success(), result.output(), result.error())));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            toolSemaphore.release();
                        }
                    }))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    private TaskResult executeTaskWithRetry(PlanTask task, String dependencySnapshot, int maxRetries) {
        Throwable lastError = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                String result = runInlineReact(task, dependencySnapshot);
                return new TaskResult(task.id(), true, result, null);
            } catch (Exception e) {
                lastError = e;
                log.warn("Task {} failed attempt {}/{}", task.id(), attempt + 1, maxRetries, e);
            }
        }
        return new TaskResult(task.id(), false, null,
                lastError == null ? "unknown error" : lastError.getMessage());
    }

    /**
     * 内嵌简化版 ReAct 循环，执行单个子任务。
     */
    private String runInlineReact(PlanTask task, String dependencySnapshot) {
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false)
                .build();

        ChatClient taskClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions)
                .defaultToolCallbacks(tools)
                .build();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(AgentDefaultPrompts.EXECUTE));
        messages.add(new UserMessage("""
                【Available Results】
                %s
                
                【Current Task】
                %s
                """.formatted(
                dependencySnapshot.isBlank() ? "NONE" : dependencySnapshot,
                task.instruction()
        )));

        for (int round = 0; round < 5; round++) {
            ChatClientResponse resp = taskClient.prompt().messages(messages).call().chatClientResponse();
            String text = resp.chatResponse().getResult().getOutput().getText();

            if (!resp.chatResponse().hasToolCalls()) {
                return text;
            }

            messages.add(AssistantMessage.builder()
                    .content(text)
                    .toolCalls(resp.chatResponse().getResult().getOutput().getToolCalls())
                    .build());

            for (AssistantMessage.ToolCall tc : resp.chatResponse().getResult().getOutput().getToolCalls()) {
                ToolCallback cb = findTool(tc.name());
                ToolResponseMessage.ToolResponse toolResp;
                if (cb == null) {
                    toolResp = new ToolResponseMessage.ToolResponse(
                            tc.id(), tc.name(), "{\"error\":\"工具未找到\"}");
                } else {
                    try {
                        toolResp = new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), cb.call(tc.arguments()).toString());
                    } catch (Exception e) {
                        toolResp = new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), "{\"error\":\"" + e.getMessage() + "\"}");
                    }
                }
                messages.add(ToolResponseMessage.builder().responses(List.of(toolResp)).build());
            }
        }

        // Force final answer after max rounds
        messages.add(new UserMessage("已达到最大推理轮次，请直接给出结论。"));
        return taskClient.prompt().messages(messages).call().content();
    }

    // ─────────────────────────────────────────────────────────────
    // Critique
    // ─────────────────────────────────────────────────────────────

    private CritiqueResult critique(OverAllState state) {
        BeanOutputConverter<CritiqueResult> converter =
                new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
                });

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(AgentDefaultPrompts.CRITIQUE));
        promptMessages.add(new UserMessage(renderMessages(state.messages)));

        String raw = chatModel.call(new Prompt(promptMessages)).getResult().getOutput().getText();
        return converter.convert(raw);
    }

    // ─────────────────────────────────────────────────────────────
    // Compress
    // ─────────────────────────────────────────────────────────────

    private void compressIfNeeded(OverAllState state, int contextCharLimit) {
        if (state.currentChars() < contextCharLimit) return;
        log.warn("Context too large ({}), compressing", state.currentChars());

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage("""
                ## 最大压缩限制（必须遵守）
                - 你输出的最终内容总字符数不得超过：%s
                
                """.formatted(contextCharLimit) + AgentDefaultPrompts.COMPRESS));
        promptMessages.add(new UserMessage(renderMessages(state.messages)));

        String snapshot = chatModel.call(new Prompt(promptMessages)).getResult().getOutput().getText();
        state.messages.clear();
        state.add(new SystemMessage("【Compressed Agent State】\n" + snapshot));
        log.info("Compress done, new size={}", state.currentChars());
    }

    // ─────────────────────────────────────────────────────────────
    // Summarize
    // ─────────────────────────────────────────────────────────────

    private String summarize(OverAllState state) {
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(AgentDefaultPrompts.SUMMARIZE));
        promptMessages.add(new UserMessage("""
                【用户原始问题】
                %s
                
                【执行上下文（含工具结果）】
                %s
                """.formatted(state.question, renderMessages(state.messages))));

        return chatModel.call(new Prompt(promptMessages)).getResult().getOutput().getText();
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String renderToolDescriptions() {
        if (tools == null || tools.isEmpty()) return "（无可用工具）";
        StringBuilder sb = new StringBuilder();
        for (ToolCallback t : tools) {
            sb.append("- ").append(t.getToolDefinition().name())
                    .append(": ").append(t.getToolDefinition().description()).append("\n");
        }
        return sb.toString();
    }

    private String renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n").append(m.getText());
        }
        return sb.toString();
    }

    private String renderDependencySnapshot(Map<String, String> results) {
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        results.forEach((id, output) ->
                sb.append("- taskId: ").append(id).append("\n  output:\n").append(output).append("\n\n"));
        return sb.toString();
    }

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst().orElse(null);
    }

    // ─────────────────────────────────────────────────────────────
    // Inner records / classes
    // ─────────────────────────────────────────────────────────────

    public record PlanTask(String id, String instruction, int order) {
    }

    public record CritiqueResult(boolean passed, String feedback) {
    }

    public record TaskResult(String taskId, boolean success, String output, String error) {
    }

    public static class OverAllState {
        final String conversationId;
        final String question;
        final List<Message> messages = new ArrayList<>();

        public OverAllState(String conversationId, String question) {
            this.conversationId = conversationId;
            this.question = question;
        }

        public void add(Message m) {
            messages.add(m);
        }

        public int currentChars() {
            return messages.stream()
                    .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                    .sum();
        }
    }
}
