package top.kdla.framework.llm.mentor.rag.agent;

import top.kdla.framework.llm.mentor.rag.agent.hitl.AgentFinished;
import top.kdla.framework.llm.mentor.rag.agent.hitl.AgentInterrupted;
import top.kdla.framework.llm.mentor.rag.agent.hitl.AgentResult;
import top.kdla.framework.llm.mentor.rag.agent.hitl.PendingToolCall;
import top.kdla.framework.llm.mentor.rag.agent.hitl.RagHITLReactAgent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Agent 聊天控制器。
 *
 * <h3>端点列表</h3>
 * <ul>
 *   <li>{@code POST /agent/chat}               — ReAct 非流式</li>
 *   <li>{@code POST /agent/chat/stream}         — ReAct SSE 流式</li>
 *   <li>{@code POST /agent/reflection/chat}     — ReAct + Reflection 非流式</li>
 *   <li>{@code POST /agent/plan-execute/chat}   — Plan-Execute 非流式</li>
 *   <li>{@code POST /agent/hitl/chat}           — HITL ReAct 非流式（可能返回中断）</li>
 *   <li>{@code POST /agent/hitl/resume}         — HITL 审批后恢复执行</li>
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final RagReactAgent ragReactAgent;
    private final RagReflectionAgent ragReflectionAgent;
    private final RagPlanExecuteAgent ragPlanExecuteAgent;
    private final RagHITLReactAgent ragHITLReactAgent;

    // ─────────────────────────────────────────────────────────────
    // ReAct
    // ─────────────────────────────────────────────────────────────

    /**
     * 非流式 Agent 聊天（ReAct）
     * POST /agent/chat
     */
    @PostMapping("/chat")
    public AgentChatResult chat(@Valid @RequestBody AgentChatParam param) {
        log.info("Agent chat: conversationId={}, question={}", param.getConversationId(), param.getQuestion());
        String answer = ragReactAgent.call(param.getConversationId(), param.getQuestion());
        return AgentChatResult.of(param.getConversationId(), answer);
    }

    /**
     * 流式 Agent 聊天（ReAct SSE）
     * POST /agent/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody AgentChatParam param) {
        log.info("Agent stream chat: conversationId={}, question={}", param.getConversationId(), param.getQuestion());
        return ragReactAgent.stream(param.getConversationId(), param.getQuestion());
    }

    // ─────────────────────────────────────────────────────────────
    // ReAct + Reflection
    // ─────────────────────────────────────────────────────────────

    /**
     * ReAct + Reflection Agent
     * POST /agent/reflection/chat
     */
    @PostMapping("/reflection/chat")
    public AgentChatResult reflectionChat(@Valid @RequestBody AgentChatParam param) {
        log.info("Reflection chat: conversationId={}, question={}", param.getConversationId(), param.getQuestion());
        String answer = ragReflectionAgent.call(param.getConversationId(), param.getQuestion());
        return AgentChatResult.of(param.getConversationId(), answer);
    }

    // ─────────────────────────────────────────────────────────────
    // Plan-Execute
    // ─────────────────────────────────────────────────────────────

    /**
     * Plan-Execute Agent
     * POST /agent/plan-execute/chat
     */
    @PostMapping("/plan-execute/chat")
    public AgentChatResult planExecuteChat(@Valid @RequestBody AgentChatParam param) {
        log.info("Plan-Execute chat: conversationId={}, question={}", param.getConversationId(), param.getQuestion());
        String answer = ragPlanExecuteAgent.call(param.getConversationId(), param.getQuestion());
        return AgentChatResult.of(param.getConversationId(), answer);
    }

    // ─────────────────────────────────────────────────────────────
    // HITL
    // ─────────────────────────────────────────────────────────────

    /**
     * HITL ReAct Agent — 初次调用。
     * POST /agent/hitl/chat
     *
     * <p>返回结果中 {@code interrupted=true} 时表示需要人工审批，
     * 客户端取 {@code pendingToolCalls} 展示给用户，确认后调用 {@code /agent/hitl/resume}。
     */
    @PostMapping("/hitl/chat")
    public HITLChatResult hitlChat(@Valid @RequestBody AgentChatParam param) {
        log.info("HITL chat: conversationId={}, question={}", param.getConversationId(), param.getQuestion());
        AgentResult result = ragHITLReactAgent.call(param.getConversationId(), param.getQuestion());
        return toHITLResult(result);
    }

    /**
     * HITL ReAct Agent — 审批后恢复。
     * POST /agent/hitl/resume
     *
     * <p>请求体示例：
     * <pre>
     * {
     *   "pendingToolCalls": [
     *     {"id": "call_xxx", "name": "getWeather", "arguments": "{...}", "result": "APPROVED"}
     *   ],
     *   "checkpointMessages": [...],
     *   "context": {...}
     * }
     * </pre>
     */
    @PostMapping("/hitl/resume")
    public HITLChatResult hitlResume(@RequestBody HITLResumeParam param) {
        log.info("HITL resume: pendingCount={}", param.getPendingToolCalls().size());
        AgentInterrupted interrupted = new AgentInterrupted(
                param.getPendingToolCalls(),
                param.getCheckpointMessages(),
                param.getContext()
        );
        AgentResult result = ragHITLReactAgent.resume(interrupted, param.getFeedbacks());
        return toHITLResult(result);
    }

    // ─────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────

    private HITLChatResult toHITLResult(AgentResult result) {
        if (result instanceof AgentFinished f) {
            return HITLChatResult.finished(f.content());
        } else if (result instanceof AgentInterrupted i) {
            return HITLChatResult.interrupted(i.pendingToolCalls());
        }
        return HITLChatResult.finished("Unknown agent result");
    }

    // ─────────────────────────────────────────────────────────────
    // DTO classes
    // ─────────────────────────────────────────────────────────────

    /** HITL 聊天响应 DTO。 */
    public static class HITLChatResult {
        public boolean interrupted;
        public String answer;
        public List<PendingToolCall> pendingToolCalls;

        public static HITLChatResult finished(String answer) {
            HITLChatResult r = new HITLChatResult();
            r.interrupted = false;
            r.answer = answer;
            return r;
        }

        public static HITLChatResult interrupted(List<PendingToolCall> pending) {
            HITLChatResult r = new HITLChatResult();
            r.interrupted = true;
            r.pendingToolCalls = pending;
            return r;
        }
    }

    /** HITL 恢复请求 DTO。 */
    public static class HITLResumeParam {
        private List<PendingToolCall> pendingToolCalls;
        private List<org.springframework.ai.chat.messages.Message> checkpointMessages;
        private Map<String, Object> context;
        private List<PendingToolCall> feedbacks;

        public List<PendingToolCall> getPendingToolCalls() { return pendingToolCalls; }
        public void setPendingToolCalls(List<PendingToolCall> v) { pendingToolCalls = v; }
        public List<org.springframework.ai.chat.messages.Message> getCheckpointMessages() { return checkpointMessages; }
        public void setCheckpointMessages(List<org.springframework.ai.chat.messages.Message> v) { checkpointMessages = v; }
        public Map<String, Object> getContext() { return context; }
        public void setContext(Map<String, Object> v) { context = v; }
        public List<PendingToolCall> getFeedbacks() { return feedbacks; }
        public void setFeedbacks(List<PendingToolCall> v) { feedbacks = v; }
    }
}
