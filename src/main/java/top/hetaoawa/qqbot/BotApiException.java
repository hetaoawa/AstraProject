package top.hetaoawa.qqbot;

/** Exception raised when QQ OpenAPI returns a non-success response or an error body. */
public final class BotApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int httpStatus;
    private final long errorCode;
    private final String traceId;

    public BotApiException(int httpStatus, long errorCode, String message, String traceId) {
        super(message == null || message.isBlank() ? "QQ Bot API request failed" : message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public long errorCode() {
        return errorCode;
    }

    public String traceId() {
        return traceId;
    }
}
