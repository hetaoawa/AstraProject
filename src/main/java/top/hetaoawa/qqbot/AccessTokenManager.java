package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class AccessTokenManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private final AstraLogger logger;
    private String token;
    private Instant expiresAt = Instant.MIN;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition refreshed = lock.newCondition();
    private boolean refreshing;

    AccessTokenManager(BotConfig config, HttpClient httpClient, AstraLogger logger) {
        this.config = config;
        this.httpClient = httpClient;
        this.logger = logger;
    }

    String get() throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (token != null && Instant.now().plusSeconds(60).isBefore(expiresAt)) {
                    logger.trace("AUTH", "using cached access token expiresAt=" + expiresAt);
                    return token;
                }
                if (!refreshing) {
                    refreshing = true;
                    break;
                }
                refreshed.await();
            }
        } finally {
            lock.unlock();
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
            refreshFailed();
            throw error;
        }
        JsonNode result;
        try {
            result = parse(response.body());
        } catch (IOException error) {
            refreshFailed();
            throw error;
        }
        if (response.statusCode() / 100 != 2 || result.path("access_token").asText().isBlank()) {
            logger.warn("AUTH", "access token request failed status=" + response.statusCode());
            refreshFailed();
            throw apiException(response.statusCode(), result, "Unable to obtain access token");
        }
        long expiresIn = result.path("expires_in").asLong(7200);
        lock.lock();
        try {
            token = result.path("access_token").asText();
            expiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn));
            refreshing = false;
            refreshed.signalAll();
        } finally {
            lock.unlock();
        }
        logger.debug("AUTH", "access token refreshed status=" + response.statusCode()
                + " expiresInSeconds=" + expiresIn + " elapsedMs=" + elapsedMillis(started));
        return token;
    }

    void invalidate() {
        lock.lock();
        try {
            token = null;
            expiresAt = Instant.MIN;
        } finally {
            lock.unlock();
        }
        logger.debug("AUTH", "access token invalidated");
    }

    private void refreshFailed() {
        lock.lock();
        try {
            refreshing = false;
            refreshed.signalAll();
        } finally {
            lock.unlock();
        }
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
