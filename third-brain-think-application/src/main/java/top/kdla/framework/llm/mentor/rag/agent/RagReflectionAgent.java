package top.kdla.framework.llm.mentor.rag.agent;

import top.kdla.framework.llm.mentor.rag.agent.advisor.VerificationAdvisor;
import top.kdla.framework.llm.mentor.rag.agent.verification.AnswerVerificationService;
import top.kdla.framework.llm.mentor.rag.agent.tool.RagToolService;
import top.kdla.framework.llm.mentor.rag.agent.tool.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ReAct + Reflection Agent。
 *
 * <p>在 {@link RagReactAgent} 基础上注入 {@link ReflectionAdvisor}：
 * 每次 LLM 给出非工具调用回答后，自动评估质量；若未通过则注入反思反馈，
 * 驱动 agent 重新规划（最多 {@code maxReflectionRounds} 次反思）。
 */
@Slf4j
@Component
public class RagReflectionAgent {

    private static final String SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct + 验证模式的智能助手，专注于利用知识库和工具解答用户问题。

            ## 工具调用规则（极其重要）
            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，且只通过工具调用字段输出。
            2. 工具调用时：禁止在 content 中出现任何形式的工具调用文本。
            3. 工具调用前后不得输出任何多余文字、标签、换行或说明。
            4. 工具参数必须是有效的 JSON，且尽量简洁。

            ## 最终答案规则
            1. 若已有完整信息，直接输出自然语言答案，禁止包含任何工具调用格式。
            2. 优先使用知识库内容作答；若知识库无相关内容，可结合通用知识回答。
            3. 回答中引用知识库内容时，使用 [文档:文档ID] 格式标注引用来源。

            ## 验证机制
            如果验证评估未通过，请根据反馈重新规划并补充必要的工具调用，然后给出更完整、更准确的答案。
            """;

    private static final int DEFAULT_MAX_ROUNDS = 8;
    private static final int DEFAULT_MAX_REFLECTION_ROUNDS = 2;

    private final ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final ChatMemory chatMemory;
    private final int maxReflectionRounds;

    public RagReflectionAgent(ChatModel chatModel,
                              RagToolService ragToolService,
                              WeatherTool weatherTool,
                              AnswerVerificationService verificationService) {
        this.tools = Arrays.asList(ToolCallbacks.from(ragToolService, weatherTool));
        this.chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
        this.maxReflectionRounds = DEFAULT_MAX_REFLECTION_ROUNDS;

        VerificationAdvisor verificationAdvisor = new VerificationAdvisor(verificationService);

        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(this.tools)
                .internalToolExecutionEnabled(false)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions)
                .defaultAdvisors(verificationAdvisor)
                .defaultToolCallbacks(this.tools)
                .build();
    }

    /**
     * 非流式调用。
     */
    public String call(String conversationId, String question) {
        List<Message> messages = buildInitialMessages(conversationId, question);
        if (conversationId != null) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        int round = 0;
        int reflectionRound = 0;

        while (true) {
            round++;
            if (round > DEFAULT_MAX_ROUNDS) {
                log.warn("Reached maxRounds({}), forcing final answer", DEFAULT_MAX_ROUNDS);
                messages.add(new UserMessage("已达到最大推理轮次，请根据当前信息直接给出最终答案，禁止再调用任何工具。"));
                String finalText = chatClient.prompt().messages(messages).call().content();
                saveMemory(conversationId, finalText);
                return finalText;
            }

            ChatClientResponse resp = chatClient.prompt().messages(messages).call().chatClientResponse();
            String aiText = resp.chatResponse().getResult().getOutput().getText();

            // Tool calls: execute and loop
            if (resp.chatResponse().hasToolCalls()) {
                messages.add(AssistantMessage.builder()
                        .content(aiText)
                        .toolCalls(resp.chatResponse().getResult().getOutput().getToolCalls())
                        .build());
                executeToolCalls(resp, messages);
                continue;
            }

            // Check if verification requires another round
            if (Boolean.TRUE.equals(resp.context().get("verification.required"))) {
                if (reflectionRound >= maxReflectionRounds) {
                    log.warn("Max verification rounds ({}) reached, returning current answer", maxReflectionRounds);
                    saveMemory(conversationId, aiText);
                    return aiText;
                }
                reflectionRound++;
                log.info("Verification round {}/{}", reflectionRound, maxReflectionRounds);
                String feedback = (String) resp.context().get("verification.feedback");
                messages.add(new AssistantMessage("""
                        【Verification Feedback】
                        %s

                        请你根据以上验证意见重新规划任务，必要时可以重新调用工具，然后再给出最终答案。
                        """.formatted(feedback)));
                continue;
            }

            // Final answer
            saveMemory(conversationId, aiText);
            return aiText;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private List<Message> buildInitialMessages(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
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

    private void executeToolCalls(ChatClientResponse resp, List<Message> messages) {
        resp.chatResponse().getResult().getOutput().getToolCalls().forEach(tc -> {
            ToolCallback cb = findTool(tc.name());
            if (cb == null) {
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), "{\"error\":\"工具未找到: " + tc.name() + "\"}")))
                        .build());
                return;
            }
            try {
                String result = cb.call(tc.arguments()).toString();
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)))
                        .build());
            } catch (Exception e) {
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                tc.id(), tc.name(), "{\"error\":\"工具执行失败: " + e.getMessage() + "\"}")))
                        .build());
            }
        });
    }

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
