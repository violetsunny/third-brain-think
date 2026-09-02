package top.kdla.framework.llm.mentor.rag.exception;

/**
 * 统一错误响应包装。
 * <p>仅用于错误响应路径（4xx/5xx），成功响应保持原有结构不变。
 *
 * @param <T> 数据载荷类型
 */
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ─────────────────────────────────────────────────────────────
    // Factory methods
    // ─────────────────────────────────────────────────────────────

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    // ─────────────────────────────────────────────────────────────
    // Accessors (Jackson serialization)
    // ─────────────────────────────────────────────────────────────

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
