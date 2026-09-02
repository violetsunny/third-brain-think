package top.kdla.framework.llm.mentor.rag.controller;

import top.kdla.framework.llm.mentor.rag.entity.ChatConversation;
import top.kdla.framework.llm.mentor.rag.entity.ChatMessage;
import top.kdla.framework.llm.mentor.rag.progress.SseEmitterHolder;
import top.kdla.framework.llm.mentor.rag.service.ChatConversationService;
import top.kdla.framework.llm.mentor.rag.service.ChatMessageService;
import top.kdla.framework.llm.mentor.rag.service.EnhancedChatService;
import top.kdla.framework.llm.mentor.rag.transformer.ContextAwareQueryTransformer;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天控制器
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {
    
    private final ChatConversationService chatConversationService;
    private final ChatMessageService chatMessageService;
    private final EnhancedChatService enhancedChatService;
    
    /**
     * 获取用户的会话列表（支持分页 + 可选时间范围过滤）
     */
    @GetMapping("/list")
    public IPage<ChatConversation> listConversations(
            @RequestParam String userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") @Max(value = 100, message = "pageSize 不能超过 100") long pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        log.info("Listing conversations for user: {}, page: {}, pageSize: {}, startTime={}, endTime={}",
                userId, page, pageSize, startTime, endTime);
        if (startTime != null || endTime != null) {
            return chatConversationService.listByTimeRange(userId, startTime, endTime, page, pageSize);
        }
        return chatConversationService.listByUserId(userId, page, pageSize);
    }

    /**
     * 重命名会话
     * PUT /chat/conversations/{conversationId}/title
     * 请求体: {"title": "新名称"}
     */
    @PutMapping("/conversations/{conversationId}/title")
    public ResponseEntity<ChatConversation> renameConversation(
            @PathVariable String conversationId,
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        log.info("Rename conversation: {}, title: {}", conversationId, title);
        try {
            chatConversationService.renameConversation(conversationId, title);
            ChatConversation updated = chatConversationService.getByConversationId(conversationId);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            // conversationId not found
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 级联删除会话（含 chat_message + Redis 缓存清除）
     * DELETE /chat/conversations/{conversationId}
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversationCascade(@PathVariable String conversationId) {
        log.info("Cascade deleting conversation: {}", conversationId);
        try {
            chatConversationService.deleteConversationCascade(conversationId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 获取会话的消息列表（支持分页）
     */
    @GetMapping("/messages")
    public IPage<ChatMessage> listMessages(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") @Max(value = 100, message = "pageSize 不能超过 100") long pageSize) {
        log.info("Listing messages for conversation: {}, page: {}, pageSize: {}", conversationId, page, pageSize);
        return chatMessageService.getMessagesByConversationId(conversationId, page, pageSize);
    }
    
    /**
     * 流式对话接口（SSE）- 增强版
     * 支持进度推送、查询转换、意图识别、RAG 引用追踪
     *
     * SSE 流格式:
     * [PROGRESS]:...    进度事件
     * [REFERENCE]:...   RAG 引用事件（JSON 数组，流末尾）
     * [DONE]:convId     结束标记
     * 其他              正文 token
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> send(
            @NotBlank(message = "userId 不能为空") @RequestParam String userId,
            @NotBlank(message = "content 不能为空") @RequestParam String content,
            @RequestParam(required = false) String conversationId) {
        
        log.info("Chat request - user: {}, conversation: {}, content length: {}",
                userId, conversationId, content.length());
        
        // 1. 处理会话：没有 conversationId 则创建新会话
        final String finalConversationId;
        if (conversationId == null || conversationId.isBlank()) {
            String tempTitle = content.substring(0, Math.min(content.length(), 20));
            finalConversationId = chatConversationService.createConversation(userId, tempTitle);
            log.info("Created new conversation: {}", finalConversationId);
        } else {
            finalConversationId = conversationId;
        }
        
        // 2. 保存用户消息
        chatMessageService.saveUserMessage(finalConversationId, content);
        
        // 3. 使用增强版聊天服务（带进度推送）
        reactor.core.publisher.Sinks.Many<String> progressSink =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();

        // 3a. 注册进度消费者到 SseEmitterHolder，使 ProgressAwareContentRetriever/Aggregator
        //     能在 RAG pipeline 深层通过 ThreadLocal 直接推送 [PROGRESS]: 事件到此 sink
        SseEmitterHolder.set(progressSink::tryEmitNext);
        // 记录请求开始时间戳，用于 elapsed 计算
        SseEmitterHolder.setRequestStartMs(System.currentTimeMillis());
        // 写入 conversationId，供 ContextAwareQueryTransformer 读取（若已启用）
        ContextAwareQueryTransformer.CONVERSATION_ID_HOLDER.set(finalConversationId);

        Flux<String> responseFlux = enhancedChatService.streamChat(
                userId,
                finalConversationId,
                content,
                progress -> progressSink.tryEmitNext(progress)
        );
        
        // 4. 将进度消息合并到响应流中
        Flux<String> progressFlux = progressSink.asFlux();
        
        // 5. 累积完整回复（区分 [REFERENCE]: 事件和正文 token）
        StringBuilder fullResponse = new StringBuilder();
        AtomicReference<String> referenceJson = new AtomicReference<>(null);

        Flux<String> mergedFlux = Flux.merge(progressFlux, responseFlux)
                .doOnNext(chunk -> {
                    if (chunk.startsWith("[REFERENCE]:")) {
                        // 捕获引用 JSON，不计入正文
                        referenceJson.set(chunk.substring("[REFERENCE]:".length()));
                    } else if (!chunk.startsWith("[PROGRESS]:")) {
                        fullResponse.append(chunk);
                    }
                })
                .doOnComplete(() -> {
                    progressSink.tryEmitComplete();

                    final String aiContent = fullResponse.toString();
                    final String refs = referenceJson.get();

                    if (!aiContent.isEmpty()) {
                        Thread.ofVirtual().name("save-msg-" + finalConversationId).start(() -> {
                            try {
                                String msgId = chatMessageService.saveAssistantMessage(
                                        finalConversationId, aiContent);
                                // 持久化 RAG 引用
                                if (refs != null && msgId != null) {
                                    chatMessageService.updateRagReferences(msgId, refs);
                                }
                                log.info("AI回复已保存: conversationId={}, length={}",
                                        finalConversationId, aiContent.length());
                            } catch (Exception e) {
                                log.error("保存AI回复失败", e);
                            }
                        });
                    }
                    log.info("聊天完成");
                })
                .doOnError(error -> {
                    progressSink.tryEmitComplete();
                    log.error("聊天出错", error);
                })
                // 统一清理 ThreadLocal：覆盖 complete / error / cancel 三种终止信号
                .doFinally(signal -> {
                    SseEmitterHolder.clear();
                    ContextAwareQueryTransformer.CONVERSATION_ID_HOLDER.remove();
                });
        
        // 6. 返回流式响应，结束时携带 conversationId
        return mergedFlux.concatWith(Flux.just("[DONE]:" + finalConversationId));
    }
    
    /**
     * 删除会话
     */
    @DeleteMapping("/{conversationId}")
    public boolean deleteConversation(@PathVariable String conversationId) {
        log.info("Deleting conversation: {}", conversationId);
        chatMessageService.deleteMessagesByConversationId(conversationId);
        return chatConversationService.deleteConversation(conversationId);
    }
}
