package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

final class HttpApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private final AccessTokenManager tokenManager;

    HttpApiClient(BotConfig config, HttpClient httpClient, AccessTokenManager tokenManager) {
        this.config = config;
        this.httpClient = httpClient;
        this.tokenManager = tokenManager;
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
        });
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
        });
    }

    CompletableFuture<Void> uploadPresignedPartAsync(URI uri, byte[] data) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        if (data == null) throw new IllegalArgumentException("data must not be null");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
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
        HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
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
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = parse(response.body());
        if (response.statusCode() / 100 != 2) {
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
}
