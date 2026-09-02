package cn.hollis.llm.mentor.ragdemo.progress;

import com.alibaba.fastjson2.JSON;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * InheritableThreadLocal-based holder for the current request's progress callback
 * and request-start timestamp.
 *
 * <p>Uses {@code InheritableThreadLocal} so the values are automatically propagated
 * to child threads created by LangChain4j's ForkJoinPool / parallel-retrieval executor.
 */
public final class SseEmitterHolder {

    private SseEmitterHolder() {}

    private static final InheritableThreadLocal<Consumer<String>> HOLDER =
            new InheritableThreadLocal<>();

    /** Request-start epoch millis — set once per request, read by retrievers/aggregators. */
    private static final InheritableThreadLocal<Long> START_MS =
            new InheritableThreadLocal<>();

    // ─────────────────────────────────────────────────────────────
    // Consumer management
    // ─────────────────────────────────────────────────────────────

    /**
     * Register a progress-event consumer for the current thread (and its child threads).
     * Call {@link #clear()} when the request completes.
     */
    public static void set(Consumer<String> progressConsumer) {
        HOLDER.set(progressConsumer);
    }

    public static Consumer<String> get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
        START_MS.remove();
    }

    // ─────────────────────────────────────────────────────────────
    // Request start time
    // ─────────────────────────────────────────────────────────────

    /**
     * Record the request start time for elapsed-time calculations in progress events.
     * Should be called immediately after {@link #set(Consumer)}.
     */
    public static void setRequestStartMs(long startMs) {
        START_MS.set(startMs);
    }

    /**
     * Returns the request start time, or the current time if not set (safe fallback).
     */
    public static long getRequestStartMs() {
        Long t = START_MS.get();
        return t != null ? t : System.currentTimeMillis();
    }

    // ─────────────────────────────────────────────────────────────
    // Push helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Safe push — silently ignores null consumer or any errors.
     */
    public static void push(String data) {
        Consumer<String> consumer = HOLDER.get();
        if (consumer == null) return;
        try {
            consumer.accept(data);
        } catch (Exception ignored) {
            // Consumer may already be closed/completed
        }
    }

    /**
     * Push a structured stage progress event.
     * Payload: {"stage":"<ENUM>","label":"<中文阶段名>","elapsed":<ms>,"done":false}
     *
     * @param stage       the RAG pipeline stage
     * @param elapsedMs   milliseconds elapsed since request start
     */
    public static void pushStage(RagProgressStage stage, long elapsedMs) {
        Consumer<String> consumer = HOLDER.get();
        if (consumer == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stage", stage.name());
            payload.put("label", stage.getLabel());
            payload.put("elapsed", elapsedMs);
            payload.put("done", false);
            consumer.accept("[PROGRESS]:" + JSON.toJSONString(payload));
        } catch (Exception ignored) {
            // Consumer may already be closed/completed
        }
    }
}


