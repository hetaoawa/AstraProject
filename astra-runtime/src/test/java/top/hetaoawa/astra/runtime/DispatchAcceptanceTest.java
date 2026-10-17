package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.api.annotation.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class DispatchAcceptanceTest {
  @TempDir Path temp;
  private final BotId bot = new BotId("bot", "production");
  private final Identity user = new Identity(bot, EntityType.USER, "member_openid", "user");
  private final ChatRef chat = new ChatRef(bot, Scene.GROUP, "chat", "");

  public static class Plugin implements AstraPlugin {
    final List<String> called = new CopyOnWriteArrayList<>();
    final AtomicInteger errors = new AtomicInteger();

    @OnCommand(
        value = "ping",
        aliases = {"p"},
        priority = 1,
        block = true)
    public HandlerResult broken(MessageEvent e, CommandArgs args) {
      called.add("broken:" + args.text());
      throw new IllegalStateException("business failure");
    }

    @OnMessage(priority = 2)
    public CompletionStage<HandlerResult> next(MessageEvent e) {
      called.add("next");
      return CompletableFuture.completedFuture(HandlerResult.CONTINUE);
    }

    public void onError(HandlerFailure failure, PluginContext context) {
      errors.incrementAndGet();
    }
  }

  @Test
  void exceptionDoesNotBlockOrRetryAndPlansSurviveCrash() {
    try (var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("dispatch.db"), "", "");
        var sessions = new SessionManager()) {
      var ledger = new JdbcLedger(db);
      var registry = new HandlerRegistry();
      var plugin = new Plugin();
      registry.register(registry.scan("p", plugin));
      var dispatcher =
          new Dispatcher(
              registry, ledger, e -> {}, sessions, (owner, b) -> context(owner, b, sessions, null));
      var event =
          new MessageEvent(
              new EventData(
                  "event", "GROUP_AT_MESSAGE_CREATE", "", chat, user, Instant.now(), Map.of()),
              new Message("m", "", "/p argument", List.of(), true, Map.of()));
      dispatcher.dispatch(event);
      dispatcher.dispatch(event);
      assertEquals(List.of("broken:argument", "next"), plugin.called);
      assertEquals(1, plugin.errors.get());
      var crashed =
          new MessageEvent(
              new EventData(
                  "crash", "GROUP_AT_MESSAGE_CREATE", "", chat, user, Instant.now(), Map.of()),
              event.message());
      var plans =
          registry.matching(crashed).stream()
              .map(h -> new ProcessingStore.Plan(h.pluginId(), h.id()))
              .toList();
      ledger.prepare(crashed, plans);
      ledger.start(crashed.key(), plans.getFirst().handlerId());
      ledger.recoverInterrupted();
      dispatcher.dispatch(crashed);
      assertEquals(List.of("broken:argument", "next", "next"), plugin.called);
    }
  }

  @Test
  void waitingSessionReleasesChatLaneAndConsumesReplyExclusively() throws Exception {
    try (var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("sessions.db"), "", "");
        var sessions = new SessionManager()) {
      var ledger = new JdbcLedger(db);
      var registry = new HandlerRegistry();
      var plugin = new Plugin();
      registry.register(registry.scan("p", plugin));
      var ctx = context("p", bot, sessions, null);
      var count = new AtomicInteger();
      ctx.sessions()
          .await(
              chat,
              user,
              e -> e.message().text().equals("hello"),
              Duration.ofSeconds(10),
              (e, c) -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(HandlerResult.CONTINUE);
              },
              null);
      assertThrows(
          IllegalStateException.class,
          () ->
              ctx.sessions()
                  .await(
                      chat,
                      user,
                      e -> true,
                      Duration.ofSeconds(1),
                      (e, c) -> CompletableFuture.completedFuture(HandlerResult.CONTINUE),
                      null));
      var dispatcher = new Dispatcher(registry, ledger, e -> {}, sessions, (owner, b) -> ctx);
      var event = StorageContractTest.message(bot, chat, user, "session", Instant.now());
      dispatcher.dispatch(event);
      dispatcher.dispatch(event);
      assertEquals(1, count.get());
      assertTrue(plugin.called.isEmpty());
      var expired = new CountDownLatch(1);
      ctx.sessions()
          .await(
              chat,
              user,
              e -> false,
              Duration.ofMillis(30),
              (e, c) -> CompletableFuture.completedFuture(HandlerResult.CONTINUE),
              expired::countDown);
      assertTrue(expired.await(2, TimeUnit.SECONDS));
      var cancelled = new AtomicInteger();
      ctx.sessions()
          .await(
              chat,
              user,
              e -> true,
              Duration.ofMillis(50),
              (e, c) -> CompletableFuture.completedFuture(HandlerResult.CONTINUE),
              cancelled::incrementAndGet);
      sessions.remove("p", bot);
      Thread.sleep(100);
      assertEquals(0, cancelled.get());
    }
  }

  public static class OrderedPlugin implements AstraPlugin {
    final CountDownLatch first = new CountDownLatch(1),
        release = new CountDownLatch(1),
        other = new CountDownLatch(1),
        done = new CountDownLatch(7);
    final List<String> order = new CopyOnWriteArrayList<>();

    @OnMessage
    public void handle(MessageEvent e) throws Exception {
      if (e.message().id().equals("0")) {
        first.countDown();
        release.await();
      }
      if (e.chat().id().equals("other")) other.countDown();
      else order.add(e.message().id());
      done.countDown();
    }
  }

  @Test
  void sameChatSerialOtherChatParallelAndCapacityIncludesInflight() throws Exception {
    var policy = new BotPolicy(bot, 2, 12, "", false);
    try (var db = new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("ordering.db"), "", "");
        var sessions = new SessionManager()) {
      var registry = new HandlerRegistry();
      var plugin = new OrderedPlugin();
      registry.register(registry.scan("p", plugin));
      var dispatcher =
          new Dispatcher(
              registry,
              new JdbcLedger(db),
              e -> {},
              sessions,
              (p, b) -> context(p, b, sessions, null));
      try (var pump =
          new EventPump(
              new MemoryEventQueue(List.of(policy)),
              new QqEventCodec(),
              dispatcher,
              List.of(policy))) {
        for (int i = 0; i < 6; i++)
          assertTrue(
              pump.accept(StorageContractTest.message(bot, chat, user, "" + i, Instant.now())));
        assertTrue(plugin.first.await(2, TimeUnit.SECONDS));
        assertTrue(
            pump.accept(
                StorageContractTest.message(
                    bot,
                    new ChatRef(bot, Scene.GROUP, "other", ""),
                    user,
                    "parallel",
                    Instant.now())));
        assertTrue(plugin.other.await(2, TimeUnit.SECONDS));
        plugin.release.countDown();
        assertTrue(plugin.done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of("0", "1", "2", "3", "4", "5"), plugin.order);
      }
    }
    try (var queue = new MemoryEventQueue(List.of(new BotPolicy(bot, 1, 1, "", false)))) {
      assertTrue(queue.offer(bot, "one"));
      var d = queue.poll(bot, Duration.ofMillis(1));
      assertFalse(queue.offer(bot, "two"));
      queue.acknowledge(d);
      queue.acknowledge(d);
      assertTrue(queue.offer(bot, "two"));
    }
  }

  @Test
  void disabledPluginHasNoTriggersAndTasksDoNotOverlap() throws Exception {
    var registry = new HandlerRegistry();
    var plugin = new Plugin();
    registry.register(registry.scan("p", plugin));
    registry.enabled("p", bot, false);
    assertTrue(
        registry
            .matching(StorageContractTest.message(bot, chat, user, "m", Instant.now()))
            .isEmpty());
    try (var tasks = new TaskManager()) {
      var active = new AtomicInteger();
      var peak = new AtomicInteger();
      var hit = new CountDownLatch(2);
      var owned = tasks.owned("p", bot);
      owned.interval(
          Duration.ZERO,
          Duration.ofMillis(10),
          () -> {
            int n = active.incrementAndGet();
            peak.accumulateAndGet(n, Math::max);
            try {
              Thread.sleep(40);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              active.decrementAndGet();
              hit.countDown();
            }
          });
      assertTrue(hit.await(3, TimeUnit.SECONDS));
      assertEquals(1, peak.get());
      tasks.remove("p", bot);
      assertEquals(0, tasks.count());
    }
  }

  static PluginContext context(
      String owner, BotId bot, SessionManager sessions, TaskManager tasks) {
    return new PluginContext() {
      public String pluginId() {
        return owner;
      }

      public BotId bot() {
        return bot;
      }

      public Messaging messaging() {
        return null;
      }

      public PlatformApi api() {
        return null;
      }

      public Directory directory() {
        return null;
      }

      public History history() {
        return null;
      }

      public Sessions sessions() {
        return sessions.owned(owner, this);
      }

      public Tasks tasks() {
        return tasks == null ? null : tasks.owned(owner, bot);
      }

      public Services services() {
        return new ServiceRegistry().owned(owner);
      }

      public Map<String, Object> configuration() {
        return Map.of();
      }
    };
  }
}
