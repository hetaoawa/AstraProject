package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.Map;

final class HttpApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private final AccessTokenManager tokenManager;
    private final AstraLogger logger;
    private final Executor httpExecutor;

    HttpApiClient(BotConfig config, HttpClient httpClient, AccessTokenManager tokenManager,
                  AstraLogger logger, Executor httpExecutor) {
        this.config = config;
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
        this.logger = logger;
        this.httpExecutor = httpExecutor;
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    CompletableFuture<JsonNode> getAsync(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return get(path);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, httpExecutor);
    }

    CompletableFuture<JsonNode> postAsync(String path, JsonNode body) {
        return requestAsync("POST", path, body);
    }

    CompletableFuture<JsonNode> requestAsync(String method, String path, JsonNode body) {
        return requestAsync(method, path, body, Map.of());
    }

    CompletableFuture<JsonNode> requestAsync(String method, String path, JsonNode body,
                                             Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(method, path, body, headers);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, httpExecutor);
    }

    CompletableFuture<Void> uploadPresignedPartAsync(URI uri, byte[] data) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        if (data == null) throw new IllegalArgumentException("data must not be null");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        long started = System.nanoTime();
        logger.debug("HTTP", "request method=PUT target=presigned-media bytes=" + data.length);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    logger.debug("HTTP", "response method=PUT target=presigned-media status="
                            + response.statusCode() + " elapsedMs=" + elapsedMillis(started));
                    if (response.statusCode() / 100 != 2) {
                        throw new BotApiException(response.statusCode(), -1,
                                "Presigned media part upload failed", null);
                    }
                    return null;
                });
    }

    private JsonNode send(String method, String path, JsonNode body) throws IOException, InterruptedException {
        return send(method, path, body, Map.of());
    }

    private JsonNode send(String method, String path, JsonNode body, Map<String, String> headers)
            throws IOException, InterruptedException {
        String token = tokenManager.get();
        URI uri = resolve(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Authorization", "QQBot " + token)
                .header("Accept", "application/json")
                .header("User-Agent", config.userAgent());
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        long started = System.nanoTime();
        logger.debug("HTTP", "request method=" + method + " path=" + safePath(uri)
                + " hasBody=" + (body != null));
        HttpResponse<String> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            logger.error("HTTP", "transport failed method=" + method + " path=" + safePath(uri)
                    + " elapsedMs=" + elapsedMillis(started), error);
            throw error;
        }
        logger.debug("HTTP", "response method=" + method + " path=" + safePath(uri)
                + " status=" + response.statusCode() + " elapsedMs=" + elapsedMillis(started));
        JsonNode result = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            logger.warn("HTTP", "request failed method=" + method + " path=" + safePath(uri)
                    + " status=" + response.statusCode() + " errorCode=" + result.path("err_code").asLong(-1));
            throw new BotApiException(
                    response.statusCode(),
                    result.path("err_code").asLong(-1),
                    result.path("message").asText("QQ Bot API request failed"),
                    result.path("trace_id").asText(null)
            );
        }
        return result;
    }

    private URI resolve(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String base = config.apiBaseUri().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return URI.create(base + normalized);
    }

    private static JsonNode parse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(body);
    }

    private static String safePath(URI uri) {
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
