package top.kdla.framework.llm.mentor.rag.event;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;

/**
 * 文档切片完成事件
 * 由 DocumentProcessServiceImpl.split() 在事务提交后发布，
 * 触发 EmbeddingEventListener 执行异步嵌入，避免在事务提交前启动异步任务导致的竞态问题。
 */
public record DocumentSplitCompletedEvent(
        String docId,
        List<TextSegment> segments,
        EmbeddingModel embeddingModel
) {
}
