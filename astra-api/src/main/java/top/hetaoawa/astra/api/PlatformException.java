package top.hetaoawa.astra.api;

public class PlatformException extends RuntimeException {
  private final int status;
  private final String code, traceId;
  private final boolean outcomeKnown;
  private final java.util.Map<String, java.util.List<String>> headers;

  public PlatformException(
      String message,
      int status,
      String code,
      String traceId,
      boolean outcomeKnown,
      Throwable cause) {
    this(message, status, code, traceId, outcomeKnown, cause, java.util.Map.of());
  }

  public PlatformException(
      String message,
      int status,
      String code,
      String traceId,
      boolean outcomeKnown,
      Throwable cause,
      java.util.Map<String, java.util.List<String>> headers) {
    super(message, cause);
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.outcomeKnown = outcomeKnown;
    this.headers = java.util.Map.copyOf(headers);
  }

  public java.util.Map<String, java.util.List<String>> headers() {
    return headers;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String traceId() {
    return traceId;
  }

  public boolean outcomeKnown() {
    return outcomeKnown;
  }
}
