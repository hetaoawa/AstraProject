package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.storage.*;

class StorageContractTest {
  @TempDir Path temp;

  @TestFactory
  Stream<DynamicTest> threeDatabaseContract() {
    Map<String, String> urls = new LinkedHashMap<>();
    urls.put("sqlite", "jdbc:sqlite:" + temp.resolve("fresh.db"));
    for (String name : List.of("postgresql", "mysql")) {
      String url = System.getProperty("astra.test." + name + ".url");
      if (url != null && !url.isBlank()) urls.put(name, url);
    }
    return urls.entrySet().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey() + " identity/history/ledger/outbound contract",
                    () -> contract(entry.getKey(), entry.getValue())));
  }

  private void contract(String dialect, String url) {
    String username =
        System.getProperty(
            "astra.test." + dialect + ".user", dialect.equals("mysql") ? "root" : "postgres");
    String password = System.getProperty("astra.test." + dialect + ".password", "");
    try (var db = new JdbcDatabase(dialect, url, username, password)) {
      String suffix = UUID.randomUUID().toString();
      var a = new BotId("a" + suffix, "production");
      var b = new BotId("b" + suffix, "production");
      var c = new BotId("c" + suffix, "sandbox");
      var d = new BotId("d" + suffix, "production");
      var policies =
          List.of(
              new BotPolicy(a, 2, 5, "shared", true),
              new BotPolicy(b, 2, 5, "shared", true),
              new BotPolicy(c, 2, 5, "other", true),
              new BotPolicy(d, 2, 5, "shared", false));
      var directory = new JdbcDirectory(db, policies);
      var ia = new Identity(a, EntityType.USER, "member_openid", "opaque:not-a-UUID");
      var ib = new Identity(b, EntityType.USER, "member_openid", ia.value());
      var ic = new Identity(c, EntityType.USER, "member_openid", ia.value());
      var id = new Identity(d, EntityType.USER, "member_openid", ia.value());
      var time = Instant.now();
      var chat = new ChatRef(a, Scene.GROUP, "same-group", "");
      directory.upsert(
          ia, Map.of("username", "Alice", "member_role", "admin"), "message", time, "", chat, true);
      directory.upsert(
          ib,
          Map.of("username", "Bob"),
          "message",
          time,
          "",
          new ChatRef(b, Scene.GROUP, "same-group", ""),
          true);
      assertEquals(1, directory.search(a, EntityType.USER, "AL", 0, 20).items().size());
      assertEquals(0, directory.search(a, EntityType.USER, "%", 0, 20).items().size());
      assertEquals("Alice", directory.find(ia).orElseThrow().fields().get("username"));
      assertEquals("Bob", directory.find(ib).orElseThrow().fields().get("username"));
      assertTrue(directory.related(ia, 0, 20).items().isEmpty());
      directory.upsert(
          ia, Map.of("username", ""), "message", time.plusSeconds(1), "union", chat, true);
      directory.upsert(ib, Map.of(), "message", time, "union", null, true);
      directory.upsert(ic, Map.of(), "message", time, "union", null, true);
      directory.upsert(id, Map.of(), "message", time, "union", null, true);
      assertEquals(2, directory.related(ia, 0, 20).items().size());
      assertTrue(directory.related(id, 0, 20).items().isEmpty());
      assertEquals("Alice", directory.find(ia).orElseThrow().fields().get("username"));
      directory.upsert(ia, Map.of(), "message", time.plusSeconds(2), "", chat, true);
      assertEquals(2, directory.related(ia, 0, 20).items().size());
      var c2c = new Identity(a, EntityType.USER, "user_openid", ia.value());
      directory.upsert(c2c, Map.of(), "c2c", time, null, null, true);
      assertNotEquals(ia.key(), c2c.key());
      assertFalse(directory.find(c2c).orElseThrow().fields().containsKey("username"));
      var groupIdentity = new Identity(a, EntityType.GROUP, "group_openid", "same-group");
      directory.upsert(groupIdentity, Map.of(), "message", time, "union", null, true);
      assertEquals(1, directory.related(groupIdentity, 0, 20).items().size());
      assertEquals(1, directory.members(chat, 0, 20).items().size());
      assertFalse(directory.find(groupIdentity).orElseThrow().complete());
      assertFalse(directory.relationships(chat, false, 0, 20).items().getFirst().complete());
      assertFalse(directory.find(ia).orElseThrow().fields().containsKey("member_role"));
      assertEquals(
          "admin",
          directory
              .relationships(chat, false, 0, 20)
              .items()
              .getFirst()
              .fields()
              .get("member_role"));
      directory.metadata("p", ia, Map.of("alias", "friend"));
      assertTrue(directory.metadata("q", ia).isEmpty());
      assertThrows(
          IllegalArgumentException.class, () -> new ScopedDirectory(directory, "p", a).find(ib));
      directory.upsert(ia, Map.of(), "leave", time.plusSeconds(3), null, chat, false);
      assertTrue(directory.members(chat, 0, 20).items().isEmpty());
      assertFalse(directory.relationships(chat, true, 0, 20).items().getFirst().active());
      var disabled = new JdbcHistory(db, false);
      assertThrows(HistoryDisabledException.class, () -> disabled.find(chat, "m", ""));
      var history = new JdbcHistory(db, true);
      for (Scene scene : List.of(Scene.CHANNEL, Scene.CHANNEL_DM)) {
        var channel = new ChatRef(a, scene, "channel" + scene, "guild");
        String key = Keys.join(suffix, scene.name());
        var data = new EventData(key, "MESSAGE_DELETE", "notice", channel, ia, time, Map.of());
        var recall = new MessageRecallEvent(data, "message-" + suffix, "");
        history.observe(recall);
        history.observe(recall);
        var message =
            new MessageEvent(
                new EventData(key + "m", "MESSAGE_CREATE", "", channel, ia, time, Map.of()),
                new Message(
                    "message-" + suffix, "index", "original text", List.of(), true, Map.of()));
        history.observe(message);
        var record = history.find(channel, message.message().id(), "index").orElseThrow();
        assertTrue(record.recalled());
        assertEquals("original text", record.message().text());
        assertEquals(
            1,
            history
                .search(new History.Query(a, channel, null, null, null, null, 0, 20))
                .items()
                .size());
      }
      for (Scene scene : List.of(Scene.GROUP, Scene.C2C)) {
        var target = new ChatRef(a, scene, "target", "");
        var e = message(a, target, ia, "not-recalled-" + scene + suffix, time);
        history.observe(e);
        history.observe(new MessageRecallEvent(e.data(), e.message().id(), ""));
        assertFalse(history.find(target, e.message().id(), "").orElseThrow().recalled());
      }
      var old = message(a, chat, ia, "old" + suffix, time.minus(Duration.ofDays(40)));
      history.observe(old);
      history.cleanup(Duration.ZERO);
      assertTrue(history.find(chat, old.message().id(), "").isPresent());
      history.cleanup(Duration.ofDays(30));
      assertTrue(history.find(chat, old.message().id(), "").isEmpty());
      var ledger = new JdbcLedger(db);
      var e = message(a, chat, ia, "ledger" + suffix, time);
      List<ProcessingStore.Plan> plan =
          List.of(new ProcessingStore.Plan("p", "h1"), new ProcessingStore.Plan("p", "h2"));
      assertEquals(plan, ledger.prepare(e, plan));
      assertTrue(ledger.start(e.key(), "h1"));
      assertFalse(ledger.start(e.key(), "h1"));
      ledger.recoverInterrupted();
      assertFalse(ledger.start(e.key(), "h1"));
      assertTrue(ledger.start(e.key(), "h2"));
      ledger.finishHandler(e.key(), "h2", "SUCCESS_BLOCK", "");
      assertTrue(ledger.blocked(e.key()));
      ledger.finishEvent(e, "DONE");
      assertTrue(ledger.completed(e.key()));
      var out = new JdbcOutboundStore(db);
      String job = UUID.randomUUID().toString();
      out.reserve(job, a.key(), "p");
      assertTrue(out.start(job));
      out.recover(true);
      assertFalse(out.start(job));
      String seq = UUID.randomUUID().toString();
      for (int i = 1; i <= 4; i++) assertEquals(i, out.nextReply(seq, 4));
      assertThrows(IllegalStateException.class, () -> out.nextReply(seq, 4));
      long now = System.currentTimeMillis();
      out.rememberReply(chat.key(), "pool-" + suffix, now + 300_000, 5);
      for (int i = 1; i <= 5; i++) {
        var reply = out.claimReply(chat.key(), null, now);
        assertEquals("pool-" + suffix, reply.messageId());
        assertEquals(i, reply.sequence());
      }
      assertNull(out.claimReply(chat.key(), null, now));
    }
    // Reopening must read the current version without executing migration DDL again.
    try (var reopened = new JdbcDatabase(dialect, url, username, password)) {
      assertEquals(
          5,
          reopened.jdbc.queryForObject("SELECT version FROM astra_schema_version", Integer.class));
    }
  }

  static MessageEvent message(BotId bot, ChatRef chat, Identity actor, String id, Instant time) {
    return new MessageEvent(
        new EventData(
            Keys.join(bot.key(), chat.key(), id),
            "MESSAGE_CREATE",
            "",
            chat,
            actor,
            time,
            Map.of()),
        new Message(id, "", "hello", List.of(), true, Map.of()));
  }
}
