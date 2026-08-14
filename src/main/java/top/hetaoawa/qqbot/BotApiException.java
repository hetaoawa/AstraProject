package top.hetaoawa.qqbot;

/** QQ OpenAPI 返回非成功状态或错误响应体时抛出的异常。 */
public final class BotApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int httpStatus;
    private final long errorCode;
    private final String traceId;

    /** 使用 QQ 返回的响应元数据创建异常。
     * @param httpStatus HTTP 状态码
     * @param errorCode QQ 错误码
     * @param message 错误消息
     * @param traceId 服务端 Trace ID
     */
    public BotApiException(int httpStatus, long errorCode, String message, String traceId) {
        super(message == null || message.isBlank() ? "QQ Bot API request failed" : message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
    }

    /** 返回 API 的 HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 返回 QQ API 错误码。 */
    public long errorCode() {
        return errorCode;
    }

    /** 返回服务端 Trace ID；如果服务端未提供则可能为空。 */
    public String traceId() {
        return traceId;
    }
}
