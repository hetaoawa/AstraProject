package top.hetaoawa.astra.api;

import java.util.Map;

public record ApiResponse(
    int status,
    Map<String, Object> data,
    String traceId,
    Map<String, java.util.List<String>> headers) {
  public ApiResponse(int status, Map<String, Object> data, String traceId) {
    this(status, data, traceId, Map.of());
  }

  public ApiResponse {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
