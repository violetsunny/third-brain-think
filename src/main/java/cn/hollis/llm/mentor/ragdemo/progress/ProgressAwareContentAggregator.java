package cn.hollis.llm.mentor.ragdemo.progress;

import cn.hollis.llm.mentor.ragdemo.reference.RagReference;
import cn.hollis.llm.mentor.ragdemo.reference.ReferenceUtil;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 装饰器：在聚合前后向 SSE 流推送结构化阶段进度事件，并在聚合完成后推送 [REFERENCE]: 事件。
 *
 * <p>阶段事件:
 * - 聚合前: RRF_FUSION（JSON）+ 兼容纯文本 "[PROGRESS]:正在排序筛选结果..."
 * - 聚合后: GENERATING（JSON）+ 兼容纯文本 "[PROGRESS]:正在生成回答..."
 *
 * <p>elapsed 从 {@link SseEmitterHolder#getRequestStartMs()} 读取，无需在构造时传入，
 * 因此可以作为单例 Bean 安全使用（requestStartMs 是 per-request ThreadLocal）。
 */
@Slf4j
@RequiredArgsConstructor
public class ProgressAwareContentAggregator implements ContentAggregator {

    private final ContentAggregator delegate;

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        long requestStartMs = SseEmitterHolder.getRequestStartMs();
        long now = System.currentTimeMillis();

        // Push structured RRF_FUSION stage event
        SseEmitterHolder.pushStage(RagProgressStage.RRF_FUSION, now - requestStartMs);
        // Fallback plain-text for legacy front-ends
        SseEmitterHolder.push("[PROGRESS]:正在排序筛选结果...");
        log.debug("Aggregation starting (elapsed={}ms)", now - requestStartMs);

        List<Content> result = delegate.aggregate(queryToContents);

        long afterAgg = System.currentTimeMillis();
        // Push structured GENERATING stage event
        SseEmitterHolder.pushStage(RagProgressStage.GENERATING, afterAgg - requestStartMs);
        // Fallback plain-text for legacy front-ends
        SseEmitterHolder.push("[PROGRESS]:正在生成回答...");
        log.debug("Aggregation complete, {} results (elapsed={}ms)", result.size(), afterAgg - requestStartMs);

        // Push [REFERENCE]: event with top retrieved chunks
        try {
            List<RagReference> refs = ReferenceUtil.fromAggregatedContents(result);
            if (!refs.isEmpty()) {
                SseEmitterHolder.push("[REFERENCE]:" + JSON.toJSONString(refs));
                log.debug("Pushed {} RAG references", refs.size());
            }
        } catch (Exception e) {
            log.warn("Failed to push RAG references: {}", e.getMessage());
        }

        return result;
    }

    public static ProgressAwareContentAggregator of(ContentAggregator delegate) {
        return new ProgressAwareContentAggregator(delegate);
    }
}


