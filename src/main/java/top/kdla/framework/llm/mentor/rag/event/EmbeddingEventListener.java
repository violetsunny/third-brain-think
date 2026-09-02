package top.kdla.framework.llm.mentor.rag.event;

import top.kdla.framework.llm.mentor.rag.embedding.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 嵌入事件监听器
 * 监听 DocumentSplitCompletedEvent，在事务提交后触发异步嵌入，
 * 避免在 @Transactional 方法内直接调用 @Async 导致的提交前竞态问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingEventListener {

    private final VectorStoreService vectorStoreService;

    /**
     * 事务提交后触发异步嵌入
     * TransactionPhase.AFTER_COMMIT 保证 knowledge_document 和 knowledge_segment 记录已持久化，
     * 嵌入线程的状态更新不会被事务回滚覆盖。
     */
    @Async("embeddingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSplitCompleted(DocumentSplitCompletedEvent event) {
        log.info("接收到切片完成事件，启动异步嵌入: docId={}, segmentCount={}",
                event.docId(), event.segments().size());
        vectorStoreService.addSegmentsAsync(event.docId(), event.segments(), event.embeddingModel());
    }
}
