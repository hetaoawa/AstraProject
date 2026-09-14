package top.hetaoawa.qqbot;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessTokenManagerTest {
    @Test
    void concurrentRefreshIsSingleFlightAndNetworkRunsOutsideStateLock() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"access_token\":\"shared\",\"expires_in\":7200}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            BotConfig config = BotConfig.builder().appId("app").clientSecret("secret")
                    .accessTokenUri("http://127.0.0.1:" + server.getAddress().getPort() + "/token")
                    .logLevel(BotLogLevel.OFF).build();
            AccessTokenManager manager = new AccessTokenManager(config, HttpClient.newHttpClient(),
                    new AstraLogger(config));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<String>>();
                for (int i = 0; i < 20; i++) futures.add(executor.submit(manager::get));
                for (Future<String> future : futures) assertEquals("shared", future.get());
            }
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
