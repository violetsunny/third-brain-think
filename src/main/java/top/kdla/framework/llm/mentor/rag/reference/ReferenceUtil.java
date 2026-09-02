package top.kdla.framework.llm.mentor.rag.reference;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * RAG 引用转换工具
 * 将 LangChain4j 检索的 Content 列表转换为 RagReference 列表
 */
@Slf4j
@UtilityClass
public class ReferenceUtil {

    private static final int MAX_CONTENT_LENGTH = 200;

    /**
     * 将多路检索结果（Map 值为 Content 列表的列表）扁平化并转为 RagReference 列表。
     *
     * @param queryToContents LangChain4j ContentAggregator 的入参（query → retriever → contents）
     * @return RagReference 列表，按 score 降序排列
     */
    public static List<RagReference> fromAggregatorInput(
            Map<?, Collection<List<Content>>> queryToContents) {

        List<RagReference> refs = new ArrayList<>();

        for (Collection<List<Content>> perRetriever : queryToContents.values()) {
            for (List<Content> contents : perRetriever) {
                for (Content content : contents) {
                    refs.add(toReference(content));
                }
            }
        }

        // De-duplicate by chunkId, keep highest score
        refs.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return dedup(refs);
    }

    /**
     * 将聚合后的有序 Content 列表转为 RagReference 列表（保留顺序，最多取 top 5）。
     */
    public static List<RagReference> fromAggregatedContents(List<Content> contents) {
        List<RagReference> refs = new ArrayList<>();
        for (Content content : contents) {
            refs.add(toReference(content));
        }
        return refs;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static RagReference toReference(Content content) {
        TextSegment segment = content.textSegment();
        String text = segment.text();

        // Metadata keys used across the codebase
        String docId    = firstNonNull(segment.metadata().getString("document_id"),
                                      segment.metadata().getString("docId"), "unknown");
        String docName  = firstNonNull(segment.metadata().getString("fileName"),
                                      segment.metadata().getString("title"), docId);
        String chunkId  = firstNonNull(segment.metadata().getString("chunkId"),
                                      segment.metadata().getString("segmentId"), "");

        // score: LangChain4j Content doesn't carry a score field directly;
        // some implementations embed it via metadata key "score"
        double score = 0.0;
        String scoreMeta = segment.metadata().getString("score");
        if (scoreMeta != null) {
            try { score = Double.parseDouble(scoreMeta); } catch (NumberFormatException ignored) {}
        }

        String chunkContent = text != null && text.length() > MAX_CONTENT_LENGTH
                ? text.substring(0, MAX_CONTENT_LENGTH) + "..."
                : text;

        return RagReference.builder()
                .docId(docId)
                .docName(docName)
                .chunkId(chunkId)
                .chunkContent(chunkContent)
                .score(score)
                .build();
    }

    private static List<RagReference> dedup(List<RagReference> refs) {
        List<RagReference> result = new ArrayList<>();
        for (RagReference ref : refs) {
            boolean found = result.stream()
                    .anyMatch(r -> r.getChunkId() != null
                            && !r.getChunkId().isEmpty()
                            && r.getChunkId().equals(ref.getChunkId()));
            if (!found) result.add(ref);
        }
        return result;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
