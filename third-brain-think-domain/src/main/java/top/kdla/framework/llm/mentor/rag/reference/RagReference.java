package top.kdla.framework.llm.mentor.rag.reference;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索引用信息
 * 随 SSE 流以 [REFERENCE]: 事件推送，并持久化到 chat_message.rag_references
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagReference {

    /** 文档唯一标识 */
    private String docId;

    /** 文档名称（来自 metadata.fileName 或 title） */
    private String docName;

    /** Chunk 唯一标识（来自 metadata.chunkId 或 segmentId） */
    private String chunkId;

    /** Chunk 内容（截断至 200 字符，供前端预览） */
    private String chunkContent;

    /** 相关性分数（来自 Content 的 relevanceScore 或 EmbeddingMatch.score） */
    private double score;
}
