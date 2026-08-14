package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

final class AccessTokenManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private String token;
    private Instant expiresAt = Instant.MIN;

    AccessTokenManager(BotConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    synchronized String get() throws IOException, InterruptedException {
        if (token != null && Instant.now().plusSeconds(60).isBefore(expiresAt)) {
            return token;
        }
        ObjectNode body = MAPPER.createObjectNode()
                .put("appId", config.appId())
                .put("clientSecret", config.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(config.accessTokenUri())
                .header("Content-Type", "application/json")
                .header("User-Agent", config.userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = parse(response.body());
        if (response.statusCode() / 100 != 2 || result.path("access_token").asText().isBlank()) {
            throw apiException(response.statusCode(), result, "Unable to obtain access token");
        }
        token = result.path("access_token").asText();
        long expiresIn = result.path("expires_in").asLong(7200);
        expiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn));
        return token;
    }

    synchronized void invalidate() {
        token = null;
        expiresAt = Instant.MIN;
    }

    private static JsonNode parse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(body);
    }

    private static BotApiException apiException(int status, JsonNode body, String fallback) {
        return new BotApiException(
                status,
                body.path("err_code").asLong(body.path("code").asLong(-1)),
                body.path("message").asText(fallback),
                body.path("trace_id").asText(null)
        );
    }
}
