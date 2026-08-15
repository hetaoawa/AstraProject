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
    private final AstraLogger logger;
    private String token;
    private Instant expiresAt = Instant.MIN;

    AccessTokenManager(BotConfig config, HttpClient httpClient, AstraLogger logger) {
        this.config = config;
        this.httpClient = httpClient;
        this.logger = logger;
    }

    synchronized String get() throws IOException, InterruptedException {
        if (token != null && Instant.now().plusSeconds(60).isBefore(expiresAt)) {
            logger.trace("AUTH", "using cached access token expiresAt=" + expiresAt);
            return token;
        }
        logger.debug("AUTH", "requesting access token");
        long started = System.nanoTime();
        ObjectNode body = MAPPER.createObjectNode()
                .put("appId", config.appId())
                .put("clientSecret", config.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(config.accessTokenUri())
                .header("Content-Type", "application/json")
                .header("User-Agent", config.userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException error) {
            logger.error("AUTH", "access token transport failed elapsedMs=" + elapsedMillis(started), error);
            throw error;
        }
        JsonNode result = parse(response.body());
        if (response.statusCode() / 100 != 2 || result.path("access_token").asText().isBlank()) {
            logger.warn("AUTH", "access token request failed status=" + response.statusCode());
            throw apiException(response.statusCode(), result, "Unable to obtain access token");
        }
        token = result.path("access_token").asText();
        long expiresIn = result.path("expires_in").asLong(7200);
        expiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn));
        logger.debug("AUTH", "access token refreshed status=" + response.statusCode()
                + " expiresInSeconds=" + expiresIn + " elapsedMs=" + elapsedMillis(started));
        return token;
    }

    synchronized void invalidate() {
        token = null;
        expiresAt = Instant.MIN;
        logger.debug("AUTH", "access token invalidated");
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

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
