package top.hetaoawa.qqbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpExecutorTest {
    private final BlockingQueue<RequestGate> gates = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange,
                "{\"access_token\":\"test-token\",\"expires_in\":7200}"));
        server.createContext("/work", exchange -> {
            try {
                RequestGate gate = gates.poll(2, TimeUnit.SECONDS);
                if (gate == null) {
                    respond(exchange, "{\"error\":\"missing gate\"}", 500);
                    return;
                }
                gate.started.countDown();
                gate.release.await(2, TimeUnit.SECONDS);
                respond(exchange, "{\"ok\":true}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                respond(exchange, "{\"error\":\"interrupted\"}", 503);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void synchronousOpenApiBlocksUntilTheResultAndPropagatesIt() throws Exception {
        BotConfig config = BotConfig.builder()
                .appId("app").clientSecret("secret")
                .apiBaseUri(baseUrl + "/")
                .accessTokenUri(baseUrl + "/token")
                .httpExecutorThreads(1)
                .logLevel(BotLogLevel.OFF)
                .build();
        try (QQBot bot = QQBot.create(config)) {
            RequestGate gate = new RequestGate();
            gates.add(gate);
            AtomicReference<com.fasterxml.jackson.databind.JsonNode> result = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> result.set(bot.api().request("GET", "/work", null)));
            assertTrue(gate.started.await(2, TimeUnit.SECONDS));
            assertTrue(caller.isAlive());
            gate.release.countDown();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertTrue(result.get().path("ok").asBoolean());
        }
        assertTrue(waitUntilThreadStops("astraqqbot-http-0-1", Duration.ofSeconds(2)));
    }

    @Test
    void validatesAndCopiesExecutorSize() {
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder()
                .appId("app").clientSecret("secret").httpExecutorThreads(0).build());

        BotConfig config = BotConfig.builder().appId("app").clientSecret("secret")
                .httpExecutorThreads(2).build();
        assertEquals(2, config.httpExecutorThreads());
        assertEquals(2, config.toBuilder().build().httpExecutorThreads());
    }

    private static boolean waitUntilThreadStops(String name, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive() && name.equals(thread.getName()));
            if (!alive) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, 200);
    }

    private static void respond(HttpExchange exchange, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class RequestGate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }
}
