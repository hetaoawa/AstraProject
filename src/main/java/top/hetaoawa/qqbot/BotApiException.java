package top.hetaoawa.qqbot;

/** Exception raised when QQ OpenAPI returns a non-success response or an error body. */
/** Exception returned when QQ OpenAPI responds with a non-success status. */
public final class BotApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int httpStatus;
    private final long errorCode;
    private final String traceId;

    /** Creates an API exception with the response metadata returned by QQ. */
    public BotApiException(int httpStatus, long errorCode, String message, String traceId) {
        super(message == null || message.isBlank() ? "QQ Bot API request failed" : message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
    }

    /** Returns the HTTP status code returned by the API. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Returns the QQ API error code. */
    public long errorCode() {
        return errorCode;
    }

    /** Returns the server trace ID, if one was supplied. */
    public String traceId() {
        return traceId;
    }
}
