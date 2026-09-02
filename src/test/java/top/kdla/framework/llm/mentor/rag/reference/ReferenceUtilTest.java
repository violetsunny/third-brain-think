package top.kdla.framework.llm.mentor.rag.reference;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReferenceUtil}.
 */
class ReferenceUtilTest {

    private static Content makeContent(String text, String docId, String chunkId, String score) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        if (docId != null)  metadata.put("document_id", docId);
        if (chunkId != null) metadata.put("chunkId", chunkId);
        if (score != null)  metadata.put("score", score);
        return Content.from(TextSegment.from(text, metadata));
    }

    /**
     * fromAggregatedContents should map each Content to a RagReference with correct fields.
     */
    @Test
    void fromAggregatedContents_mapsFieldsCorrectly() {
        Content c = makeContent("chunk text here", "doc-1", "chunk-1", "0.85");

        List<RagReference> refs = ReferenceUtil.fromAggregatedContents(List.of(c));

        assertThat(refs).hasSize(1);
        RagReference ref = refs.get(0);
        assertThat(ref.getDocId()).isEqualTo("doc-1");
        assertThat(ref.getChunkId()).isEqualTo("chunk-1");
        assertThat(ref.getScore()).isEqualTo(0.85);
        assertThat(ref.getChunkContent()).isEqualTo("chunk text here");
    }

    /**
     * Long content (>200 chars) should be truncated with "..." suffix.
     */
    @Test
    void fromAggregatedContents_longContentTruncated() {
        String longText = "A".repeat(250);
        Content c = makeContent(longText, "doc-2", "chunk-2", null);

        List<RagReference> refs = ReferenceUtil.fromAggregatedContents(List.of(c));

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getChunkContent()).endsWith("...");
        assertThat(refs.get(0).getChunkContent().length()).isLessThanOrEqualTo(203); // 200 + "..."
    }

    /**
     * fromAggregatorInput should flatten multi-retriever results and deduplicate by chunkId.
     */
    @Test
    void fromAggregatorInput_deduplicatesByChunkId() {
        Content c1 = makeContent("text one", "doc-1", "chunk-dup", "0.9");
        Content c2 = makeContent("text two", "doc-1", "chunk-dup", "0.7"); // same chunkId → dedup
        Content c3 = makeContent("text three", "doc-2", "chunk-3", "0.8");

        // Simulate two retrievers returning results for the same query
        Collection<List<Content>> retriever1 = List.of(List.of(c1));
        Collection<List<Content>> retriever2 = List.of(List.of(c2, c3));
        Map<String, Collection<List<Content>>> input = Map.of("query", List.of(List.of(c1, c2, c3)));

        List<RagReference> refs = ReferenceUtil.fromAggregatorInput(input);

        // chunk-dup should appear exactly once (highest-score kept due to sort+dedup order)
        long dupCount = refs.stream().filter(r -> "chunk-dup".equals(r.getChunkId())).count();
        assertThat(dupCount).isEqualTo(1);
    }
}
