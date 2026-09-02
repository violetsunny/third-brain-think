package cn.hollis.llm.mentor.ragdemo.progress;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 装饰器：在委托检索器执行前向 SSE 流推送结构化阶段进度事件。
 * SseEmitter 通过 SseEmitterHolder (InheritableThreadLocal) 获取，不存在时静默跳过。
 *
 * <p>elapsed 从 {@link SseEmitterHolder#getRequestStartMs()} 读取，无需在构造时传入，
 * 因此可以作为单例 Bean 安全使用。
 *
 * <p>同时保留旧式 push(String) 纯文本路径，用于无 stage 语义的场景（向后兼容）。
 */
@Slf4j
public class ProgressAwareContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    /** 兼容旧式纯文本进度消息（当 stage 为 null 时使用） */
    private final String progressMessage;
    /** 结构化阶段枚举（优先使用） */
    private final RagProgressStage stage;

    private ProgressAwareContentRetriever(ContentRetriever delegate,
                                           String progressMessage,
                                           RagProgressStage stage) {
        this.delegate = delegate;
        this.progressMessage = progressMessage;
        this.stage = stage;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (stage != null) {
            long elapsed = System.currentTimeMillis() - SseEmitterHolder.getRequestStartMs();
            SseEmitterHolder.pushStage(stage, elapsed);
            log.debug("Stage pushed: {} (elapsed={}ms)", stage, elapsed);
        } else {
            SseEmitterHolder.push("[PROGRESS]:" + progressMessage);
            log.debug("Progress pushed: {}", progressMessage);
        }
        return delegate.retrieve(query);
    }

    // ─────────────────────────────────────────────────────────────
    // Factory methods
    // ─────────────────────────────────────────────────────────────

    /** Legacy factory: plain-text progress message, no stage semantics. */
    public static ProgressAwareContentRetriever of(ContentRetriever delegate, String progressMessage) {
        return new ProgressAwareContentRetriever(delegate, progressMessage, null);
    }

    /**
     * Structured factory: push a stage JSON event with elapsed time from SseEmitterHolder.
     *
     * @param delegate the real retriever to delegate to
     * @param stage    which RAG pipeline stage this retriever represents
     */
    public static ProgressAwareContentRetriever of(ContentRetriever delegate, RagProgressStage stage) {
        return new ProgressAwareContentRetriever(delegate, null, stage);
    }
}
