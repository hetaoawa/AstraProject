package top.hetaoawa.astra.runtime;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import top.hetaoawa.astra.api.*;

@RestController
public final class PluginHttpController {
  private final AstraRuntime runtime;

  public PluginHttpController(AstraRuntime runtime) {
    this.runtime = runtime;
  }

  @RequestMapping("/**")
  public ResponseEntity<byte[]> route(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    var found = runtime.routes().find(request.getMethod(), path);
    if (found.isEmpty()) return ResponseEntity.notFound().build();
    var route = found.get();
    if (request.getContentLengthLong() > 2 * 1024 * 1024) return ResponseEntity.status(413).build();
    try {
      var body = request.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
      if (body.length > 2 * 1024 * 1024) return ResponseEntity.status(413).build();
      var headers = new LinkedHashMap<String, List<String>>();
      for (String name : Collections.list(request.getHeaderNames()))
        headers.put(name, List.copyOf(Collections.list(request.getHeaders(name))));
      var query = new LinkedHashMap<String, List<String>>();
      if (request.getQueryString() != null)
        for (String pair : request.getQueryString().split("&")) {
          var parts = pair.split("=", 2);
          String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
          String value =
              parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
          query.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
      var input =
          new HttpRoutes.Request(
              request.getMethod(),
              path,
              runtime.routes().variables(route, path),
              query,
              headers,
              body,
              request.getRemoteAddr());
      var completed = new CompletableFuture<HttpRoutes.Response>();
      Thread.startVirtualThread(
          () -> {
            try (var lease = runtime.registry().acquire(route.owner(), route.bot())) {
              if (lease == null || !runtime.routes().active(route)) {
                completed.complete(HttpRoutes.Response.text(503, "Plugin route unavailable"));
                return;
              }
              try {
                completed.complete(route.handler().handle(input).toCompletableFuture().get());
              } catch (Throwable error) {
                try {
                  runtime.routeError(
                      route,
                      error instanceof ExecutionException && error.getCause() != null
                          ? error.getCause()
                          : error);
                } catch (Throwable callbackError) {
                  error.addSuppressed(callbackError);
                }
                completed.completeExceptionally(error);
              }
            }
          });
      // A timed-out HTTP response does not release the plugin's in-flight lease or retry business.
      var response = completed.get(30, TimeUnit.SECONDS);
      var result = ResponseEntity.status(response.status());
      response
          .headers()
          .forEach((name, values) -> values.forEach(value -> result.header(name, value)));
      return result.body(response.body());
    } catch (Throwable error) {
      return ResponseEntity.status(error instanceof TimeoutException ? 504 : 500).build();
    }
  }
}
