package io.github.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

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
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
    }

    CompletableFuture<JsonNode> postAsync(String path, ObjectNode body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send("POST", path, body);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
    }

    private JsonNode send(String method, String path, ObjectNode body) throws IOException, InterruptedException {
        String token = tokenManager.get();
        HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
                .header("Authorization", "QQBot " + token)
                .header("Accept", "application/json")
                .header("User-Agent", config.userAgent());
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
