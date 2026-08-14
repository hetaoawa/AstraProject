package top.hetaoawa.qqbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQOpenApiTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> callbackAppId = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange,
                "{\"access_token\":\"test-token\",\"expires_in\":7200}"));
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().toString());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            callbackAppId.set(exchange.getRequestHeaders().getFirst("X-Callback-AppID"));
            assertEquals("QQBot test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, "{\"ok\":true}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsNamedManagementAndMessageEndpoints() {
        try (QQBot bot = QQBot.create(BotConfig.builder()
                .appId("app").clientSecret("secret")
                .apiBaseUri(baseUrl + "/")
                .accessTokenUri(baseUrl + "/token")
                .logLevel(BotLogLevel.OFF)
                .build())) {
            bot.api().updateGroupRestrictChatSetting("group id",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("op", "add").put("member_openid", "member-1")).join();
            assertEquals("POST", lastMethod.get());
            assertEquals("/v2/groups/group%20id/restrict_chat_setting", lastPath.get());
            assertTrue(lastBody.get().contains("member_openid"));

            bot.api().updateChannel("channel-1",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("name", "new")).join();
            assertEquals("PATCH", lastMethod.get());
            assertEquals("/channels/channel-1", lastPath.get());

            bot.api().listGuilds(null, "guild cursor", 20).join();
            assertEquals("GET", lastMethod.get());
            assertEquals("/users/@me/guilds?after=guild%20cursor&limit=20", lastPath.get());

            bot.api().uploadPrivateMedia("user-1",
                    RichMediaRequest.fromUrl(RichMediaRequest.IMAGE, "https://example.com/a.png")
                            .serverSendsMessage(false)).join();
            assertEquals("/v2/users/user-1/files", lastPath.get());
            assertTrue(lastBody.get().contains("file_type"));

            bot.api().respondInteraction("interaction-1", 0).join();
            assertEquals("PUT", lastMethod.get());
            assertEquals("app", callbackAppId.get());

            bot.api().createSchedule("channel-1",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("name", "meeting")).join();
            assertTrue(lastBody.get().contains("\"schedule\""));

            bot.api().removeGuildMember("guild-1", "user-1", true, 7).join();
            assertEquals("DELETE", lastMethod.get());
            assertTrue(lastBody.get().contains("delete_history_msg_days"));
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
