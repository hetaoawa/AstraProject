package top.hetaoawa.astra.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Plugin HTTP handlers share the runtime's application listener. */
public interface HttpRoutes {
  Handle register(String method, String path, Handler handler);

  @FunctionalInterface
  interface Handler {
    CompletionStage<Response> handle(Request request) throws Exception;
  }

  interface Handle extends AutoCloseable {
    void close();
  }

  record Request(
      String method,
      String path,
      Map<String, String> pathVariables,
      Map<String, List<String>> query,
      Map<String, List<String>> headers,
      byte[] body,
      String remoteAddress) {
    public Request {
      body = body.clone();
      pathVariables = Map.copyOf(pathVariables);
      query = Map.copyOf(query);
      headers = Map.copyOf(headers);
    }

    public byte[] body() {
      return body.clone();
    }

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  record Response(int status, Map<String, List<String>> headers, byte[] body) {
    public Response {
      if (status < 200 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
      headers = Map.copyOf(headers);
      body = body.clone();
    }

    public byte[] body() {
      return body.clone();
    }

    public static Response text(int status, String value) {
      return new Response(
          status,
          Map.of("Content-Type", List.of("text/plain; charset=UTF-8")),
          value.getBytes(StandardCharsets.UTF_8));
    }

    public static Response json(int status, String value) {
      return new Response(
          status,
          Map.of("Content-Type", List.of("application/json; charset=UTF-8")),
          value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
