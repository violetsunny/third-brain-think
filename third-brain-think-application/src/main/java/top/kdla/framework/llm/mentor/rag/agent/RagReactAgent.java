package top.kdla.framework.llm.mentor.rag.agent;

import top.kdla.framework.llm.mentor.rag.agent.tool.CalculatorTool;
import top.kdla.framework.llm.mentor.rag.agent.tool.CodeExecutorTool;
import top.kdla.framework.llm.mentor.rag.agent.tool.RagToolService;
import top.kdla.framework.llm.mentor.rag.agent.tool.WeatherTool;
import top.kdla.framework.llm.mentor.rag.ai.StreamThinkTagFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG + ReAct Agent
 *
 * <p>基于 SimpleReactAgent 模式，整合 RAG 知识库检索工具和天气查询工具。
 * 作为 Spring Bean 注入，通过 {@code ChatModel}（Spring AI）驱动。
 */
@Slf4j
@Component
public class RagReactAgent {

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct 模式的智能助手，专注于利用知识库和工具解答用户问题。

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
    private final ChatMemory chatMemory;

    public RagReactAgent(ChatModel chatModel,
                         RagToolService ragToolService,
                         WeatherTool weatherTool,
                         CalculatorTool calculatorTool,
                         CodeExecutorTool codeExecutorTool) {
        this.tools = Arrays.asList(ToolCallbacks.from(ragToolService, weatherTool, calculatorTool, codeExecutorTool));
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        // internalToolExecutionEnabled=false so we drive the ReAct loop manually.
        // Tools are registered once via defaultToolCallbacks; do NOT also set them
        // on ToolCallingChatOptions.toolCallbacks to avoid duplicate registration.
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions)
                .defaultToolCallbacks(this.tools)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Non-streaming call
    // ─────────────────────────────────────────────────────────────

    public String call(String conversationId, String question) {
        List<Message> messages = buildInitialMessages(conversationId, question);
        if (conversationId != null) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        int round = 0;
        while (true) {
            round++;
            if (round > DEFAULT_MAX_ROUNDS) {
                log.warn("Reached maxRounds({}), forcing final answer", DEFAULT_MAX_ROUNDS);
                messages.add(new UserMessage("已达到最大推理轮次，请根据当前信息直接给出最终答案，禁止再调用任何工具。"));
                String finalText = chatClient.prompt().messages(new ArrayList<>(messages)).call().content();
                saveMemory(conversationId, finalText);
                return finalText;
            }

            ChatClientResponse resp = chatClient.prompt().messages(new ArrayList<>(messages)).call().chatClientResponse();
            String aiText = resp.chatResponse().getResult().getOutput().getText();

            if (!resp.chatResponse().hasToolCalls()) {
                saveMemory(conversationId, aiText);
                return aiText;
            }

            // Execute tool calls and append results
            messages.add(AssistantMessage.builder()
                    .content(aiText)
                    .toolCalls(resp.chatResponse().getResult().getOutput().getToolCalls())
                    .build());

            resp.chatResponse().getResult().getOutput().getToolCalls().forEach(tc -> {
                ToolCallback cb = findTool(tc.name());
                if (cb == null) {
                    messages.add(errorToolResponse(tc, "工具未找到: " + tc.name()));
                    return;
                }
                try {
                    String result = cb.call(tc.arguments()).toString();
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)))
                            .build());
                } catch (Exception e) {
                    messages.add(errorToolResponse(tc, "工具执行失败: " + e.getMessage()));
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Streaming call
    // ─────────────────────────────────────────────────────────────

    public Flux<String> stream(String conversationId, String question) {
        List<Message> messages = buildInitialMessages(conversationId, question);
        if (conversationId != null) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicLong roundCounter = new AtomicLong(0);
        AtomicBoolean done = new AtomicBoolean(false);

        scheduleStreamRound(messages, sink, roundCounter, done, conversationId);

        return StreamThinkTagFilter.filter(
                sink.asFlux()
                        .doOnCancel(() -> done.set(true)));
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private List<Message> buildInitialMessages(String conversationId, String question) {
        // Use a plain ArrayList — access is always from a single logical thread per round.
        // executeAndContinue takes a snapshot before passing to chatClient to avoid
        // ConcurrentModificationException when tool results are appended concurrently.
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        if (conversationId != null) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null) messages.addAll(history);
        }

        messages.add(new UserMessage(question));
        return messages;
    }

    private void saveMemory(String conversationId, String text) {
        if (conversationId != null) {
            chatMemory.add(conversationId, new AssistantMessage(text));
        }
    }

    private void scheduleStreamRound(List<Message> messages, Sinks.Many<String> sink,
                                     AtomicLong roundCounter, AtomicBoolean done,
                                     String conversationId) {
        roundCounter.incrementAndGet();

        // Per-round state
        StringBuilder textBuf = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean hasToolCall = new AtomicBoolean(false);

        chatClient.prompt()
                .messages(new ArrayList<>(messages))
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    Generation gen = chunk.getResult();
                    List<AssistantMessage.ToolCall> tcs = gen.getOutput().getToolCalls();
                    String text = gen.getOutput().getText();

                    if (tcs != null && !tcs.isEmpty()) {
                        hasToolCall.set(true);
                        tcs.forEach(tc -> mergeToolCall(toolCalls, tc));
                        return;
                    }
                    if (text != null) {
                        sink.tryEmitNext(text);
                        textBuf.append(text);
                    }
                })
                .doOnComplete(() -> {
                    if (!hasToolCall.get()) {
                        // Final answer
                        saveMemory(conversationId, textBuf.toString());
                        sink.tryEmitComplete();
                        done.set(true);
                        return;
                    }

                    if (roundCounter.get() >= DEFAULT_MAX_ROUNDS) {
                        forceFinalStream(messages, sink, done, conversationId);
                        return;
                    }

                    messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());
                    executeAndContinue(toolCalls, messages, sink, roundCounter, done, conversationId);
                })
                .doOnError(err -> {
                    if (!done.get()) {
                        done.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
    }

    private void executeAndContinue(List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                    Sinks.Many<String> sink, AtomicLong roundCounter, AtomicBoolean done,
                                    String conversationId) {
        AtomicInteger completed = new AtomicInteger(0);
        int total = toolCalls.size();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (done.get()) {
                    // Already done — just count the completion; do not schedule next round.
                    completed.incrementAndGet();
                    return;
                }
                ToolCallback cb = findTool(tc.name());
                if (cb == null) {
                    messages.add(errorToolResponse(tc, "工具未找到: " + tc.name()));
                } else {
                    try {
                        String result = cb.call(tc.arguments()).toString();
                        messages.add(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)))
                                .build());
                    } catch (Exception e) {
                        messages.add(errorToolResponse(tc, "工具执行失败: " + e.getMessage()));
                    }
                }
                if (completed.incrementAndGet() >= total && !done.get()) {
                    scheduleStreamRound(messages, sink, roundCounter, done, conversationId);
                }
            });
        }
    }

    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink,
                                  AtomicBoolean done, String conversationId) {
        messages.add(new UserMessage("已达到最大推理轮次，请根据当前信息直接给出最终答案，禁止再调用任何工具。"));
        StringBuilder buf = new StringBuilder();
        chatClient.prompt().messages(new ArrayList<>(messages)).stream().chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !done.get()) {
                        sink.tryEmitNext(text);
                        buf.append(text);
                    }
                })
                .doOnComplete(() -> {
                    done.set(true);
                    sink.tryEmitComplete();
                    saveMemory(conversationId, buf.toString());
                })
                .doOnError(err -> {
                    done.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    private void mergeToolCall(List<AssistantMessage.ToolCall> list, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < list.size(); i++) {
            AssistantMessage.ToolCall existing = list.get(i);
            if (existing.id().equals(incoming.id())) {
                String mergedArgs = Objects.toString(existing.arguments(), "")
                        + Objects.toString(incoming.arguments(), "");
                list.set(i, new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs));
                return;
            }
        }
        list.add(incoming);
    }

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Message errorToolResponse(AssistantMessage.ToolCall tc, String msg) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), "{\"error\":\"" + msg + "\"}")))
                .build();
    }
}
