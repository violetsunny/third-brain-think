package top.kdla.framework.llm.mentor.rag.exception;

/**
 * 业务异常。
 * <p>由 Service 层抛出，由 {@link GlobalExceptionHandler} 统一捕获并返回 HTTP 400。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
