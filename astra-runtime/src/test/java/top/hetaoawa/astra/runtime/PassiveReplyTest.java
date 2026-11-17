package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class PassiveReplyTest {
  @TempDir Path temp;
  private final BotId bot = new BotId("bot", "production");

  @Test
  void versionFourUpgradePreservesExistingDataAndCanBeReopened() {
    String url = "jdbc:sqlite:" + temp.resolve("upgrade.db");
    try (var db = new JdbcDatabase("sqlite", url, "", "")) {
      db.jdbc.execute("DROP TABLE astra_reply_contexts");
      db.jdbc.update("UPDATE astra_schema_version SET version=4");
      db.jdbc.update(
          "INSERT INTO astra_plugin_state(plugin_id,bot_key,enabled_flag)"
              + " VALUES('plugin','bot',1)");
    }
    for (int i = 0; i < 2; i++) {
      try (var db = new JdbcDatabase("sqlite", url, "", "")) {
        assertEquals(
            5, db.jdbc.queryForObject("SELECT version FROM astra_schema_version", Integer.class));
        assertEquals(
            1,
            db.jdbc.queryForObject(
                "SELECT enabled_flag FROM astra_plugin_state WHERE plugin_id='plugin'",
                Integer.class));
        var store = new JdbcOutboundStore(db);
        store.rememberReply("chat", "id", System.currentTimeMillis() + 300_000, 5);
        assertEquals(i + 1, store.claimReply("chat", "id", System.currentTimeMillis()).sequence());
      }
    }
  }

  @Test
  void contextsSurviveRestartAndDuplicateObservationsNeverReplenishOrExtendThem() {
    String url = "jdbc:sqlite:" + temp.resolve("restart.db");
    String chat = new ChatRef(bot, Scene.GROUP, "group", "").key();
    long now = System.currentTimeMillis(), expiry = now + 300_000;
    try (var db = new JdbcDatabase("sqlite", url, "", "")) {
      var store = new JdbcOutboundStore(db);
      store.rememberReply(chat, "message", expiry, 5);
      assertEquals(1, store.claimReply(chat, "message", now).sequence());
      store.rememberReply(chat, "message", expiry + 100_000, 50);
      assertEquals(expiry, store.claimReply(chat, null, now).expiresAt());
      assertEquals(
          1, db.jdbc.queryForObject("SELECT COUNT(*) FROM astra_reply_contexts", Integer.class));
    }
    try (var db = new JdbcDatabase("sqlite", url, "", "")) {
      var store = new JdbcOutboundStore(db);
      for (int i = 3; i <= 5; i++) assertEquals(i, store.claimReply(chat, null, now).sequence());
      assertNull(store.claimReply(chat, null, now));
      store.rememberReply(chat, "message", expiry, 5);
      assertNull(store.claimReply(chat, "message", now));
      store.rememberReply(chat, "boundary", expiry, 5);
      assertNull(store.claimReply(chat, "boundary", expiry));
      store.cleanup(0);
    }
  }

  @Test
  void concurrentPluginsShareOneCounterAndRelationsRemainIsolated() throws Exception {
    try (var db = database("concurrency")) {
      var store = new JdbcOutboundStore(db);
      var group = new ChatRef(bot, Scene.GROUP, "same", "");
      var c2c = new ChatRef(bot, Scene.C2C, "same", "");
      var otherBot = new ChatRef(new BotId("other", "production"), Scene.GROUP, "same", "");
      var sandbox = new ChatRef(new BotId("bot", "sandbox"), Scene.GROUP, "same", "");
      long now = System.currentTimeMillis();
      for (var chat : List.of(group, c2c, otherBot, sandbox))
        store.rememberReply(
            chat.key(), "same-id", now + 300_000, chat.scene() == Scene.C2C ? 4 : 5);
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var calls = new ArrayList<Future<OutboundStore.Reply>>();
        for (int i = 0; i < 30; i++)
          calls.add(workers.submit(() -> store.claimReply(group.key(), null, now)));
        var sequences = new HashSet<Integer>();
        int granted = 0;
        for (var call : calls) {
          var reply = call.get(5, TimeUnit.SECONDS);
          if (reply != null) {
            granted++;
            sequences.add(reply.sequence());
          }
        }
        assertEquals(5, granted);
        assertEquals(Set.of(1, 2, 3, 4, 5), sequences);
      }
      for (int i = 1; i <= 4; i++)
        assertEquals(i, store.claimReply(c2c.key(), null, now).sequence());
      assertNull(store.claimReply(c2c.key(), null, now));
      assertEquals(1, store.claimReply(otherBot.key(), null, now).sequence());
      assertEquals(1, store.claimReply(sandbox.key(), null, now).sequence());
      assertNull(store.claimReply(new ChatRef(bot, Scene.GROUP, "different", "").key(), null, now));
    }
  }

  @Test
  void explicitAndAutomaticMessagesShareIdsAndOnlyExhaustedPoolsUseActiveSending()
      throws Exception {
    List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    var server = server(requests);
    try (var db = database("messages")) {
      var store = new JdbcOutboundStore(db);
      var routes = new RouteCatalog();
      var limits =
          new OutboundLimits(bot, routes, OutboundLimits.Policy.defaults(), new JdbcQuotaStore(db));
      var api =
          new DirectQqApi(
              new BotCredentials(
                  bot,
                  "secret",
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                  Duration.ofSeconds(3)),
              routes,
              limits);
      var backend = new MemoryEventQueue(List.of(new BotPolicy(bot, 1, 100, "", false)));
      try (var queue =
          new OutboundQueue(backend, store, Map.of(bot, api), (job, response, failure) -> {})) {
        var first =
            message("GROUP_MESSAGE_CREATE", "first", Instant.now().minusSeconds(2), "index1");
        var second = message("GROUP_AT_MESSAGE_CREATE", "second", Instant.now(), "index2");
        queue.observe(first);
        queue.observe(second);
        var a = new QqMessaging("a", bot, queue, store);
        var b = new QqMessaging("b", bot, queue, store);
        await(a.reply(second, Map.of("content", "explicit", "is_wakeup", true)));
        assertEquals("second", requests.getLast().get("msg_id"));
        assertFalse(requests.getLast().containsKey("is_wakeup"));
        // Re-delivery with a different event index must still spend the same message ID's budget.
        queue.observe(
            message("GROUP_MESSAGE_CREATE", "first", first.data().occurredAt(), "other-index"));
        for (int i = 1; i <= 5; i++) {
          await(b.send(first.chat(), MessagePayload.text("automatic")));
          assertEquals("first", requests.getLast().get("msg_id"));
          assertEquals(i, requests.getLast().get("msg_seq"));
        }
        assertThrows(
            ExecutionException.class,
            () -> a.reply(first, MessagePayload.text("exhausted")).toCompletableFuture().get());
        assertEquals(6, requests.size());
        for (int i = 2; i <= 5; i++) {
          await(a.send(first.chat(), MessagePayload.text("automatic")));
          assertEquals("second", requests.getLast().get("msg_id"));
          assertEquals(i, requests.getLast().get("msg_seq"));
        }
        await(a.send(first.chat(), MessagePayload.text("active")));
        assertFalse(requests.getLast().containsKey("msg_id"));
        var expired =
            message("GROUP_MESSAGE_CREATE", "expired", Instant.now().minusSeconds(300), "");
        queue.observe(expired);
        assertThrows(
            ExecutionException.class,
            () -> a.reply(expired, MessagePayload.text("expired")).toCompletableFuture().get());
        await(a.send(expired.chat(), MessagePayload.text("active")));
        assertFalse(requests.getLast().containsKey("msg_id"));

        var privateMessage =
            message("C2C_MESSAGE_CREATE", "private", Instant.now().minusSeconds(600), "");
        queue.observe(privateMessage);
        for (int i = 1; i <= 4; i++) {
          await(a.send(privateMessage.chat(), MessagePayload.text("private")));
          assertEquals("private", requests.getLast().get("msg_id"));
          assertEquals(i, requests.getLast().get("msg_seq"));
        }
        await(b.send(privateMessage.chat(), MessagePayload.text("active")));
        assertFalse(requests.getLast().containsKey("msg_id"));
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void passiveReuseStillWorksAfterActiveDailyBudgetIsSpent() throws Exception {
    var requests = new CopyOnWriteArrayList<Map<String, Object>>();
    var server = server(requests);
    try (var db = database("daily")) {
      var routes = new RouteCatalog();
      var limits =
          new OutboundLimits(bot, routes, OutboundLimits.Policy.defaults(), new JdbcQuotaStore(db));
      var api =
          new DirectQqApi(
              new BotCredentials(
                  bot,
                  "secret",
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                  Duration.ofSeconds(3)),
              routes,
              limits);
      var store = new JdbcOutboundStore(db);
      try (var queue =
          new OutboundQueue(
              new MemoryEventQueue(List.of(new BotPolicy(bot, 1, 10, "", false))),
              store,
              Map.of(bot, api),
              (job, response, failure) -> {})) {
        var source = message("GROUP_MESSAGE_CREATE", "pooled", Instant.now(), "");
        var messaging = new QqMessaging("p", bot, queue, store);
        await(messaging.send(source.chat(), MessagePayload.text("active")));
        db.jdbc.update(
            "UPDATE astra_quotas SET used_count=1000 WHERE quota_key IN"
                + " (SELECT quota_key FROM astra_quotas WHERE used_count=1)");
        queue.observe(source);
        await(messaging.send(source.chat(), MessagePayload.text("reuse")));
        assertEquals("pooled", requests.getLast().get("msg_id"));
        assertEquals(2, requests.size());
      }
    } finally {
      server.stop(0);
    }
  }

  private JdbcDatabase database(String name) {
    return new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve(name + ".db"), "", "");
  }

  private MessageEvent message(String type, String id, Instant time, String index) {
    var raw = new LinkedHashMap<String, Object>();
    raw.put("id", id);
    raw.put("timestamp", time.toString());
    raw.put("content", "hello");
    raw.put("message_scene", Map.of("ext", List.of("msg_idx=" + index)));
    if (type.startsWith("GROUP")) {
      raw.put("group_openid", "group");
      raw.put("author", Map.of("member_openid", "member"));
    } else raw.put("author", Map.of("user_openid", "user"));
    return (MessageEvent) new QqEventCodec().normalize(bot, Map.of("t", type, "d", raw));
  }

  private static void await(CompletionStage<ApiResponse> response) throws Exception {
    response.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static HttpServer server(List<Map<String, Object>> requests) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String answer;
            if (exchange.getRequestURI().getPath().equals("/app/getAppAccessToken")) {
              answer = "{\"access_token\":\"test\",\"expires_in\":7200}";
            } else {
              requests.add(
                  QqJson.map(
                      new String(
                          exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
              answer = "{\"id\":\"sent\"}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return server;
  }
}
