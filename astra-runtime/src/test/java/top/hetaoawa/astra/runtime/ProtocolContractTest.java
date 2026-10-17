package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class ProtocolContractTest {
  @TempDir Path temp;

  @Test
  void officialEd25519ChallengeAndRawBytes() {
    var signature = new WebhookSignature("DG5g3B4j9X2KOErG");
    String timestamp = "1725442341", token = "Arq0D5A61EgUu4OxUvOp";
    String expected =
        "87befc99c42c651b3aac0278e71ada338433ae26fcb24307bdc5ad38c1adc2d01bcfcadc0842edac85e85205028a1132afe09280305f13aa6909ffc2d652c706";
    assertEquals(expected, signature.sign(timestamp, token));
    assertTrue(signature.verify(timestamp, expected, token.getBytes(StandardCharsets.UTF_8), null));
    assertFalse(
        signature.verify(
            timestamp, expected, (token + " ").getBytes(StandardCharsets.UTF_8), null));
    assertFalse(
        signature.verify(
            timestamp, expected, token.getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)));
  }

  @Test
  void scenesOpaqueIdsUnknownContentAndRecallArePreserved() {
    var codec = new QqEventCodec();
    var bot = new BotId("b", "production");
    var raw =
        Map.<String, Object>of(
            "id",
            "opaque",
            "group_openid",
            "group",
            "author",
            Map.of("member_openid", "user", "union_openid", ""),
            "content",
            "hello",
            "message_scene",
            Map.of("ext", List.of("msg_idx=INDEX")),
            "unknown",
            Map.of("future", true));
    var e =
        (MessageEvent) codec.normalize(bot, Map.of("op", 0, "t", "GROUP_MESSAGE_CREATE", "d", raw));
    assertEquals(Scene.GROUP, e.chat().scene());
    assertEquals("member_openid", e.actor().kind());
    assertEquals("INDEX", e.message().index());
    assertEquals(raw, codec.decode(codec.encode(e)).data().raw());
    assertInstanceOf(
        RawEvent.class, codec.normalize(bot, Map.of("t", "FUTURE_EVENT", "d", Map.of("x", 1))));
    for (String type :
        List.of("MESSAGE_DELETE", "PUBLIC_MESSAGE_DELETE", "DIRECT_MESSAGE_DELETE")) {
      var r =
          codec.normalize(
              bot,
              Map.of(
                  "t",
                  type,
                  "d",
                  Map.of(
                      "message",
                      Map.of("id", "m", "channel_id", "c", "guild_id", "g"),
                      "op_user",
                      Map.of("id", "u"))));
      assertInstanceOf(MessageRecallEvent.class, r);
      assertEquals(type.startsWith("DIRECT") ? Scene.CHANNEL_DM : Scene.CHANNEL, r.chat().scene());
    }
    var c2c =
        (MessageEvent)
            codec.normalize(
                bot,
                Map.of(
                    "t",
                    "C2C_MESSAGE_CREATE",
                    "d",
                    Map.of("id", "c2c", "author", Map.of("user_openid", "user"))));
    assertEquals(Scene.C2C, c2c.chat().scene());
    assertNotEquals(e.actor(), c2c.actor());
  }

  @Test
  void everyCatalogRouteUsesAuthenticatedHttpAndNoBusinessRetry() throws Exception {
    var fixtureNode =
        QqJson.MAPPER.readTree(getClass().getResourceAsStream("/qq/operation-fixtures.json"));
    Map<String, Map<String, Object>> fixtures = new LinkedHashMap<>();
    for (var fixture : fixtureNode)
      fixtures.put(
          fixture.path("operation").asText(), QqJson.MAPPER.convertValue(fixture, Map.class));
    var expectedReply = new AtomicReference<Map<String, Object>>(Map.of());
    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    AtomicInteger tokenCalls = new AtomicInteger(), failureCalls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getRawPath();
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String answer = QqJson.write(expectedReply.get());
            if (path.equals("/app/getAppAccessToken")) {
              tokenCalls.incrementAndGet();
              answer = "{\"access_token\":\"test-token\",\"expires_in\":\"7200\"}";
            } else {
              seen.add(
                  Map.of(
                      "method",
                      exchange.getRequestMethod(),
                      "path",
                      path,
                      "auth",
                      String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                      "body",
                      body,
                      "contentType",
                      String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"))));
              if (path.equals("/fail")) {
                failureCalls.incrementAndGet();
                status = 403;
                answer = "{\"code\":11253,\"message\":\"capability unavailable\"}";
              }
              if (path.equals("/upload")) answer = "";
            }
            exchange.getResponseHeaders().set("X-Tps-Trace-Id", "trace");
            exchange.getResponseHeaders().set("ETag", "part-etag");
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var bot = new BotId("sample", "production");
      var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var routes = new RouteCatalog();
      var api =
          new DirectQqApi(new BotCredentials(bot, "secret", base, Duration.ofSeconds(3)), routes);
      assertTrue(routes.operations().size() >= 90);
      for (var operation : routes.operations().values()) {
        Map<String, String> params = new LinkedHashMap<>();
        var match = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(operation.template());
        while (match.find()) params.put(match.group(1), "ID/value");
        var fixture = fixtures.get(operation.id());
        var requestBody =
            operation.method().equals("GET")
                ? Map.<String, Object>of()
                : QqJson.object(fixture.get("request"));
        var replyBody = QqJson.object(fixture.get("response"));
        if (replyBody.containsKey("code")
            && !Set.of("0", "").contains(QqJson.string(replyBody.get("code"))))
          replyBody = Map.of();
        expectedReply.set(replyBody);
        var response =
            api.call(operation.id(), params, Map.of("limit", 1), requestBody)
                .toCompletableFuture()
                .get();
        assertEquals(200, response.status());
        var request = seen.getLast();
        assertEquals(operation.method(), request.get("method"));
        assertEquals("QQBot test-token", request.get("auth"));
        assertEquals(RouteCatalog.expand(operation.template(), params), request.get("path"));
        assertEquals(replyBody, response.data());
        if (!requestBody.isEmpty())
          assertEquals(requestBody, QqJson.map(request.get("body").toString()));
      }
      expectedReply.set(Map.of());
      assertEquals(1, tokenCalls.get());
      var error =
          assertThrows(
              ExecutionException.class,
              () -> api.raw("POST", "/fail", Map.of(), Map.of()).toCompletableFuture().get());
      var platform = (PlatformException) error.getCause();
      assertEquals("11253", platform.code());
      assertEquals("trace", platform.traceId());
      assertTrue(platform.outcomeKnown());
      assertEquals(1, failureCalls.get());
      var multipart =
          api.multipart(
                  "POST",
                  "/multipart",
                  Map.of("content", "中文"),
                  Map.of(
                      "file_image",
                      new UploadFile("sample.png", "image/png", new byte[] {1, 2, 3})))
              .toCompletableFuture()
              .get();
      assertEquals(200, multipart.status());
      assertTrue(seen.getLast().get("contentType").toString().startsWith("multipart/form-data;"));
      assertTrue(seen.getLast().get("body").toString().contains("name=\"file_image\""));
      assertEquals(
          "part-etag",
          api.uploadPart(base + "/upload", new byte[] {1, 2}, Map.of())
              .toCompletableFuture()
              .get()
              .data()
              .get("etag"));
      try (var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("out.db"), "", "")) {
        var store = new JdbcOutboundStore(db);
        var policy = new BotPolicy(bot, 1, 5, "", false);
        try (var queue =
            new OutboundQueue(
                new MemoryEventQueue(List.of(policy)),
                store,
                Map.of(bot, api),
                Map.of(bot, 1000d),
                (job, response) -> {})) {
          assertThrows(
              ExecutionException.class,
              () ->
                  queue
                      .owned("p", bot)
                      .raw("POST", "/fail", Map.of(), Map.of())
                      .toCompletableFuture()
                      .get(3, TimeUnit.SECONDS));
          assertEquals(2, failureCalls.get());
          assertThrows(
              ExecutionException.class,
              () ->
                  queue
                      .submit(
                          "p",
                          bot,
                          "POST",
                          "/expired",
                          Map.of(),
                          Map.of(),
                          null,
                          null,
                          Map.of(),
                          System.currentTimeMillis() - 1)
                      .toCompletableFuture()
                      .get(3, TimeUnit.SECONDS));
          assertTrue(seen.stream().noneMatch(x -> x.get("path").equals("/expired")));
        }
      }
    } finally {
      server.stop(0);
    }
  }
}
