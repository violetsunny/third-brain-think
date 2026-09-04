package top.kdla.framework.llm.mentor.rag.ai;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link StreamThinkTagFilter}.
 */
class StreamThinkTagFilterTest {

    /**
     * Normal content with no think tags passes through unchanged.
     */
    @Test
    void noThinkTag_passesThroughUnchanged() {
        Flux<String> source = Flux.just("Hello ", "world");
        StepVerifier.create(StreamThinkTagFilter.filter(source))
                .expectNext("Hello ")
                .expectNext("world")
                .verifyComplete();
    }

    /**
     * Content inside a complete <think>...</think> block is discarded;
     * surrounding content is emitted (may be in one or more chunks).
     */
    @Test
    void singleChunk_thinkBlockDiscarded() {
        Flux<String> source = Flux.just("Before<think>thinking...</think>After");
        StepVerifier.create(StreamThinkTagFilter.filter(source).collectList())
                .expectNextMatches(list -> {
                    String joined = String.join("", list);
                    return joined.contains("Before") && joined.contains("After")
                            && !joined.contains("thinking...")
                            && !joined.contains("<think>")
                            && !joined.contains("</think>");
                })
                .verifyComplete();
    }

    /**
     * <think> and </think> tags may arrive in separate token chunks.
     */
    @Test
    void splitAcrossTokens_thinkBlockDiscarded() {
        Flux<String> source = Flux.just("Start", "<think>", "hidden content", "</think>", "End");
        StepVerifier.create(StreamThinkTagFilter.filter(source).collectList())
                .expectNextMatches(list -> {
                    String joined = String.join("", list);
                    return joined.contains("Start") && joined.contains("End")
                            && !joined.contains("hidden content")
                            && !joined.contains("<think>")
                            && !joined.contains("</think>");
                })
                .verifyComplete();
    }

    /**
     * If stream ends while still inside an unclosed <think> block,
     * buffered content is discarded and the stream completes cleanly.
     */
    @Test
    void unclosedThinkBlock_bufferedContentDiscarded() {
        Flux<String> source = Flux.just("Visible", "<think>never closed");
        StepVerifier.create(StreamThinkTagFilter.filter(source).collectList())
                .expectNextMatches(list -> {
                    String joined = String.join("", list);
                    return joined.contains("Visible")
                            && !joined.contains("never closed");
                })
                .verifyComplete();
    }
}
