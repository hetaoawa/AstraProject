package top.hetaoawa.astra.qq;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import top.hetaoawa.astra.api.*;

public final class DirectQqApi implements PlatformApi {
  private final BotCredentials bot;
  private final HttpClient client;
  private final TokenManager tokens;
  private final RouteCatalog routes;
  private final OutboundLimits limits;

  public DirectQqApi(BotCredentials bot, RouteCatalog routes) {
    this(
        bot,
        routes,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public DirectQqApi(BotCredentials bot, RouteCatalog routes, HttpClient client) {
    this(bot, routes, client, null);
  }

  public DirectQqApi(BotCredentials bot, RouteCatalog routes, OutboundLimits limits) {
    this(
        bot,
        routes,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        limits);
  }

  public DirectQqApi(
      BotCredentials bot, RouteCatalog routes, HttpClient client, OutboundLimits limits) {
    this.bot = bot;
    this.routes = routes;
    this.client = client;
    this.limits = limits;
    tokens = new TokenManager(bot, client, limits);
    if (limits != null)
      limits.resolver((method, path) -> execute(method, path, Map.of(), Map.of()).data());
  }

  public OutboundLimits limits() {
    return limits;
  }

  private OutboundLimits.Permit admit(
      String method, String path, Map<String, Object> body, boolean external, Runnable before)
      throws InterruptedException {
    if (limits == null) {
      before.run();
      return () -> {};
    }
    return limits.acquire(method, path, body, external, before);
  }

  String token() {
    return tokens.token();
  }

  public Map<String, ApiOperation> operations() {
    Map<String, ApiOperation> result = new LinkedHashMap<>();
    routes
        .operations()
        .forEach(
            (k, v) ->
                result.put(
                    k,
                    new ApiOperation(k, v.method(), v.template(), v.title(), v.documentation())));
    return Collections.unmodifiableMap(result);
  }

  public CompletionStage<ApiResponse> call(
      String operation,
      Map<String, String> path,
      Map<String, Object> query,
      Map<String, Object> body) {
    var op = routes.require(operation);
    return raw(op.method(), routes.path(op, path), query, body);
  }

  public CompletionStage<ApiResponse> raw(
      String method, String path, Map<String, Object> query, Map<String, Object> body) {
    try {
      return CompletableFuture.completedFuture(execute(method, path, query, body));
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public ApiResponse execute(
      String method, String path, Map<String, Object> query, Map<String, Object> body) {
    return execute(method, path, query, body, () -> {});
  }

  ApiResponse execute(
      String method,
      String path,
      Map<String, Object> query,
      Map<String, Object> body,
      Runnable before) {
    if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)
        || !path.startsWith("/")
        || path.startsWith("//")
        || path.contains("..")
        || path.contains("?")
        || path.contains("#")) throw new IllegalArgumentException("Invalid relative API request");
    String suffix =
        query.isEmpty()
            ? ""
            : "?"
                + query.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(e -> encode(e.getKey()) + "=" + encode(String.valueOf(e.getValue())))
                    .collect(java.util.stream.Collectors.joining("&"));
    URI uri = bot.apiBase().resolve(path + suffix);
    if (!Objects.equals(uri.getHost(), bot.apiBase().getHost()))
      throw new IllegalArgumentException("API host mismatch");
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(bot.timeout())
            .header("Content-Type", "application/json");
    if (!path.equals("/app/getAppAccessToken"))
      builder.header("Authorization", "QQBot " + tokens.token());
    builder.method(
        method,
        body.isEmpty() && Set.of("GET", "DELETE").contains(method)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(QqJson.write(body)));
    boolean transmitted = false;
    try (var permit = admit(method, path, body, false, before)) {
      transmitted = true;
      return response(
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString()),
          method,
          path,
          body,
          false);
    } catch (PlatformException e) {
      throw e;
    } catch (CancellationException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new PlatformException(
          "QQ API call failed; request was not retried", 0, "", "", !transmitted, e);
    }
  }

  @SuppressWarnings("unchecked")
  private ApiResponse response(
      HttpResponse<String> response,
      String method,
      String path,
      Map<String, Object> request,
      boolean external) {
    String trace = response.headers().firstValue("X-Tps-Trace-Id").orElse("");
    Map<String, Object> data;
    try {
      if (response.body().isBlank()) data = Map.of();
      else {
        Object value = QqJson.MAPPER.readValue(response.body(), Object.class);
        data = value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("items", value);
      }
    } catch (Exception e) {
      if (limits != null)
        limits.feedback(
            method,
            path,
            request,
            response.statusCode(),
            response.headers().map(),
            Map.of(),
            external);
      throw new PlatformException(
          "Malformed QQ API response",
          response.statusCode(),
          "",
          trace,
          true,
          e,
          response.headers().map());
    }
    if (trace.isBlank()) trace = QqJson.string(data.get("trace_id"));
    String code = QqJson.string(data.getOrDefault("err_code", data.get("code")));
    if (limits != null)
      limits.feedback(
          method, path, request, response.statusCode(), response.headers().map(), data, external);
    if (response.statusCode() / 100 != 2 || !code.isBlank() && !code.equals("0")) {
      if (response.statusCode() == 401) tokens.invalidate();
      throw new PlatformException(
          QqJson.string(
              data.getOrDefault("message", data.getOrDefault("msg", "QQ API rejected request"))),
          response.statusCode(),
          code,
          trace,
          true,
          null,
          response.headers().map());
    }
    return new ApiResponse(
        response.statusCode(), Collections.unmodifiableMap(data), trace, response.headers().map());
  }

  public CompletionStage<ApiResponse> uploadPart(
      String uploadUrl, byte[] bytes, Map<String, String> headers) {
    return uploadPart(uploadUrl, bytes, headers, () -> {});
  }

  CompletionStage<ApiResponse> uploadPart(
      String uploadUrl, byte[] bytes, Map<String, String> headers, Runnable before) {
    boolean transmitted = false;
    try {
      URI uri = URI.create(uploadUrl);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid upload URL");
      var b =
          HttpRequest.newBuilder(uri)
              .timeout(bot.timeout())
              .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
      headers.forEach(
          (k, v) -> {
            if (k.equalsIgnoreCase("Authorization") || k.equalsIgnoreCase("Host"))
              throw new IllegalArgumentException(
                  "Upload authorization must be supplied by platform URL");
            b.header(k, v);
          });
      try (var permit = admit("PUT", uploadUrl, Map.of(), true, before)) {
        transmitted = true;
        var uploaded = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (uploaded.statusCode() / 100 == 2)
          return CompletableFuture.completedFuture(
              new ApiResponse(
                  uploaded.statusCode(),
                  Map.of("etag", uploaded.headers().firstValue("ETag").orElse("")),
                  uploaded.headers().firstValue("X-Tps-Trace-Id").orElse(""),
                  uploaded.headers().map()));
        return CompletableFuture.completedFuture(
            response(uploaded, "PUT", uploadUrl, Map.of(), true));
      }
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(
          e instanceof PlatformException || e instanceof CancellationException
              ? e
              : new PlatformException(
                  "Upload part failed; not retried", 0, "", "", !transmitted, e));
    }
  }

  public CompletionStage<ApiResponse> multipart(
      String method, String path, Map<String, String> fields, Map<String, UploadFile> files) {
    return multipart(method, path, fields, files, () -> {});
  }

  CompletionStage<ApiResponse> multipart(
      String method,
      String path,
      Map<String, String> fields,
      Map<String, UploadFile> files,
      Runnable before) {
    boolean transmitted = false;
    try {
      if (!Set.of("POST", "PUT", "PATCH").contains(method)
          || !path.startsWith("/")
          || path.startsWith("//")
          || path.contains("..")
          || path.contains("?")
          || path.contains("#"))
        throw new IllegalArgumentException("Invalid multipart API request");
      String boundary = "astra-" + UUID.randomUUID();
      var data = new java.io.ByteArrayOutputStream();
      for (var field : fields.entrySet()) {
        validateField(field.getKey());
        data.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + field.getKey()
                    + "\"\r\n\r\n"
                    + field.getValue()
                    + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      for (var file : files.entrySet()) {
        validateField(file.getKey());
        var f = file.getValue();
        data.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + file.getKey()
                    + "\"; filename=\""
                    + f.name()
                    + "\"\r\nContent-Type: "
                    + f.contentType()
                    + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        data.write(f.data());
        data.write("\r\n".getBytes(StandardCharsets.UTF_8));
      }
      data.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      var request =
          HttpRequest.newBuilder(bot.apiBase().resolve(path))
              .timeout(bot.timeout())
              .header("Authorization", "QQBot " + tokens.token())
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .method(method, HttpRequest.BodyPublishers.ofByteArray(data.toByteArray()))
              .build();
      try (var permit = admit(method, path, new LinkedHashMap<>(fields), false, before)) {
        transmitted = true;
        return CompletableFuture.completedFuture(
            response(
                client.send(request, HttpResponse.BodyHandlers.ofString()),
                method,
                path,
                new LinkedHashMap<>(fields),
                false));
      }
    } catch (Throwable error) {
      return CompletableFuture.failedFuture(
          error instanceof PlatformException || error instanceof CancellationException
              ? error
              : new PlatformException(
                  "Multipart request failed; not retried", 0, "", "", !transmitted, error));
    }
  }

  private static void validateField(String key) {
    if (!key.matches("[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException("Invalid multipart field");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
