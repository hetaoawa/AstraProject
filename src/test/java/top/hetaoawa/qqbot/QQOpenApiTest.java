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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQOpenApiTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> callbackAppId = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

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
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
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
                            .put("op", "add").put("member_openid", "member-1"));
            assertEquals("POST", lastMethod.get());
            assertEquals("/v2/groups/group%20id/restrict_chat_setting", lastPath.get());
            assertTrue(lastBody.get().contains("member_openid"));

            bot.api().updateChannel("channel-1",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("name", "new"));
            assertEquals("PATCH", lastMethod.get());
            assertEquals("/channels/channel-1", lastPath.get());

            bot.api().listGuilds(null, "guild cursor", 20);
            assertEquals("GET", lastMethod.get());
            assertEquals("/users/@me/guilds?after=guild%20cursor&limit=20", lastPath.get());

            bot.api().uploadPrivateMedia("user-1",
                    RichMediaRequest.fromUrl(RichMediaRequest.IMAGE, "https://example.com/a.png")
                            .serverSendsMessage(false));
            assertEquals("/v2/users/user-1/files", lastPath.get());
            assertTrue(lastBody.get().contains("file_type"));

            bot.api().respondInteraction("interaction-1", 0);
            assertEquals("PUT", lastMethod.get());
            assertEquals("app", callbackAppId.get());

            bot.api().createSchedule("channel-1",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("name", "meeting"));
            assertTrue(lastBody.get().contains("\"schedule\""));

            bot.api().removeGuildMember("guild-1", "user-1", true, 7);
            assertEquals("DELETE", lastMethod.get());
            assertTrue(lastBody.get().contains("delete_history_msg_days"));

            bot.api().listGroupJoinRequests("group-1", "next cursor", 50);
            assertEquals("/v2/groups/group-1/join_request_list?cursor=next%20cursor&limit=50", lastPath.get());

            bot.api().listGroupMembers("group-1", "member cursor");
            assertEquals("/v2/groups/group-1/members?cursor=member%20cursor", lastPath.get());

            bot.api().getGroupMember("group-1", "member 1");
            assertEquals("/v2/groups/group-1/members/member%201", lastPath.get());

            var members = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            members.putArray("member_openids").add("member-1");
            bot.api().batchRemoveGroupMembers("group-1", members);
            assertEquals("/v2/groups/group-1/batch_remove_members", lastPath.get());
            assertEquals("POST", lastMethod.get());

            bot.api().listGroupMemberBlacklist("group-1", "blacklist cursor", 100);
            assertEquals("/v2/groups/group-1/member_blacklist?cursor=blacklist%20cursor&limit=100", lastPath.get());

            bot.api().updateGroupMemberBlacklist("group-1",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("op", "add"));
            assertEquals("/v2/groups/group-1/member_blacklist", lastPath.get());

            bot.api().createDirectMessage("user 1", "guild 1");
            assertEquals("/users/@me/dms", lastPath.get());
            assertTrue(lastBody.get().contains("source_guild_id"));

            bot.api().recallDirectMessage("dm guild", "message 1", true);
            assertEquals("/dms/dm%20guild/messages/message%201?hidetip=true", lastPath.get());
        }
    }

    @Test
    void mapsChannelAndDirectMessageEndpointsWithoutC2cFields() {
        try (QQBot bot = QQBot.create(BotConfig.builder()
                .appId("app").clientSecret("secret")
                .apiBaseUri(baseUrl + "/")
                .accessTokenUri(baseUrl + "/token")
                .logLevel(BotLogLevel.OFF)
                .build())) {
            bot.sendChannelMessage("channel 1", MessagePayload.markdown("hello").messageSequence(2));
            assertEquals("/channels/channel%201/messages", lastPath.get());
            assertFalse(lastBody.get().contains("msg_type"));
            assertFalse(lastBody.get().contains("msg_seq"));

            bot.sendDirectMessage("dm guild", MessagePayload.embed(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("title", "notice")));
            assertEquals("/dms/dm%20guild/messages", lastPath.get());
            assertTrue(lastBody.get().contains("embed"));

            bot.sendChannelImage("channel-1", MessagePayload.text("caption"),
                    "picture.png", "image/png", new byte[]{1, 2, 3});
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            assertTrue(lastBody.get().contains("name=\"file_image\"; filename=\"picture.png\""));
            assertTrue(lastBody.get().contains("name=\"content\""));

            var directData = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            directData.put("id", "message-1").put("guild_id", "dm-guild").put("channel_id", "dm-channel");
            directData.putObject("author").put("id", "user-1");
            var direct = QQMessageEvent.from(bot,
                    new QQEvent("event-1", 0, 1L, "DIRECT_MESSAGE_CREATE", directData, directData));
            direct.replyText("reply");
            assertEquals("/dms/dm-guild/messages", lastPath.get());
            assertTrue(lastBody.get().contains("msg_id"));

            var channelData = directData.deepCopy();
            channelData.put("channel_id", "channel-2").put("guild_id", "guild-2");
            var channel = QQMessageEvent.from(bot,
                    new QQEvent("event-2", 0, 2L, "AT_MESSAGE_CREATE", channelData, channelData));
            channel.replyText("reply");
            assertEquals("/channels/channel-2/messages", lastPath.get());
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
