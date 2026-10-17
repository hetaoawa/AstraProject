package top.hetaoawa.astra.qq;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import org.slf4j.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class QqGateway implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(QqGateway.class);
  private final BotCredentials bot;
  private final DirectQqApi api;
  private final TokenManager tokens;
  private final GatewayStore store;
  private final QqEventCodec codec;
  private final Predicate<Event> receiver;
  private final java.util.function.Consumer<String> packets;
  private final long intents;
  private final int shards;
  private final boolean durable;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final ExecutorService work = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final List<Connection> connections = new CopyOnWriteArrayList<>();
  private long remaining = Long.MAX_VALUE, resetAt = 0, nextIdentify = 0;
  private boolean budgetLoaded = false;

  public QqGateway(
      BotCredentials bot,
      DirectQqApi api,
      GatewayStore store,
      QqEventCodec codec,
      Predicate<Event> receiver,
      long intents,
      int shards,
      boolean durable) {
    this(bot, api, store, codec, receiver, intents, shards, durable, packet -> {});
  }

  public QqGateway(
      BotCredentials bot,
      DirectQqApi api,
      GatewayStore store,
      QqEventCodec codec,
      Predicate<Event> receiver,
      long intents,
      int shards,
      boolean durable,
      java.util.function.Consumer<String> packets) {
    if (shards < 1 || shards > 128 || intents < 0)
      throw new IllegalArgumentException("Invalid gateway subscription");
    this.bot = bot;
    this.api = api;
    this.store = store;
    this.codec = codec;
    this.receiver = receiver;
    this.packets = packets;
    this.intents = intents;
    this.shards = shards;
    this.durable = durable;
    this.tokens = new TokenManager(bot, client);
    for (int i = 0; i < shards; i++) {
      Connection c = new Connection(i);
      connections.add(c);
      clock.schedule(() -> work.submit(c::connect), i * 5L, TimeUnit.SECONDS);
    }
  }

  private synchronized void identifyPermit(Map<String, Object> info) throws InterruptedException {
    long now = System.currentTimeMillis();
    if (!budgetLoaded || now >= resetAt) {
      var limit = QqJson.object(info.get("session_start_limit"));
      remaining = QqJson.number(limit.get("remaining"), Long.MAX_VALUE);
      resetAt = now + QqJson.number(limit.get("reset_after"), 86400000);
      budgetLoaded = true;
    }
    if (remaining < 1)
      throw new IllegalStateException(
          "Gateway session-start budget exhausted until " + Instant.ofEpochMilli(resetAt));
    long delay = nextIdentify - now;
    if (delay > 0) Thread.sleep(delay);
    remaining--;
    nextIdentify = System.currentTimeMillis() + 5500;
  }

  private final class Connection implements WebSocket.Listener {
    final int shard;
    volatile GatewayStore.Position position;
    volatile WebSocket socket;
    volatile ScheduledFuture<?> heartbeat;
    volatile boolean awaitingAck;
    volatile boolean connected;
    volatile boolean fatal;
    final AtomicBoolean reconnecting = new AtomicBoolean();
    final StringBuilder chunks = new StringBuilder();
    private CompletableFuture<Void> sending = CompletableFuture.completedFuture(null);
    volatile Map<String, Object> gatewayInfo = Map.of();

    Connection(int shard) {
      this.shard = shard;
      this.position = durable ? store.load(bot.bot(), shard) : new GatewayStore.Position("", -1);
    }

    void connect() {
      if (!running.get() || fatal) return;
      reconnecting.set(false);
      try {
        if (!api.operations().isEmpty()) {
          gatewayInfo = api.execute("GET", "/gateway/bot", Map.of(), Map.of()).data();
        }
        String url = QqJson.string(gatewayInfo.get("url"));
        if (url.isBlank()) throw new IllegalStateException("Gateway URL missing");
        URI uri = URI.create(url);
        if (!Set.of("wss", "ws").contains(uri.getScheme()))
          throw new IllegalStateException("Invalid gateway URL");
        client
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(uri, this)
            .whenComplete(
                (ws, error) -> {
                  if (error != null) reconnect("connect failed");
                });
      } catch (Throwable error) {
        LOG.warn(
            "Gateway connection unavailable for bot {} shard {}: {}",
            bot.bot(),
            shard,
            error.getClass().getSimpleName());
        reconnect("connect failed");
      }
    }

    public void onOpen(WebSocket ws) {
      if (!running.get()) {
        ws.abort();
        return;
      }
      socket = ws;
      connected = false;
      chunks.setLength(0);
      ws.request(1);
    }

    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      if (ws != socket) return CompletableFuture.completedFuture(null);
      try {
        if (chunks.length() + data.length() > 2 * 1024 * 1024)
          throw new IllegalArgumentException("Gateway payload exceeds 2 MiB");
        chunks.append(data);
        if (last) {
          String payload = chunks.toString();
          chunks.setLength(0);
          packets.accept(payload);
          receive(QqJson.map(payload));
        }
      } catch (Throwable error) {
        LOG.warn(
            "Gateway input rejected for bot {} shard {}; reconnecting from accepted sequence",
            bot.bot(),
            shard);
        reconnect("input not accepted");
      }
      if (running.get() && ws == socket) ws.request(1);
      return CompletableFuture.completedFuture(null);
    }

    void receive(Map<String, Object> payload) throws Exception {
      int op = (int) QqJson.number(payload.get("op"), -1);
      switch (op) {
        case 10 -> {
          long interval =
              QqJson.number(QqJson.object(payload.get("d")).get("heartbeat_interval"), 45000);
          if (interval < 1000 || interval > 300000)
            throw new IllegalArgumentException("Invalid heartbeat interval");
          if (heartbeat != null) heartbeat.cancel(false);
          awaitingAck = false;
          heartbeat =
              clock.scheduleAtFixedRate(
                  () -> {
                    if (awaitingAck) {
                      reconnect("heartbeat acknowledgement missing");
                      return;
                    }
                    beat();
                  },
                  interval,
                  interval,
                  TimeUnit.MILLISECONDS);
          work.submit(
              () -> {
                try {
                  String token = "QQBot " + api.token();
                  if (!position.session().isBlank())
                    send(
                        Map.of(
                            "op",
                            6,
                            "d",
                            Map.of(
                                "token",
                                token,
                                "session_id",
                                position.session(),
                                "seq",
                                position.sequence())));
                  else {
                    identifyPermit(gatewayInfo);
                    send(
                        Map.of(
                            "op",
                            2,
                            "d",
                            Map.of(
                                "token",
                                token,
                                "intents",
                                intents,
                                "shard",
                                List.of(shard, shards),
                                "properties",
                                Map.of(
                                    "$os",
                                    System.getProperty("os.name"),
                                    "$browser",
                                    "astra",
                                    "$device",
                                    "astra"))));
                  }
                } catch (Throwable e) {
                  reconnect("authentication failed");
                }
              });
        }
        case 11 -> awaitingAck = false;
        case 1 -> beat();
        case 7 -> reconnect("server reconnect");
        case 9 -> {
          if (!Boolean.TRUE.equals(payload.get("d"))) {
            position = new GatewayStore.Position("", -1);
            if (durable) store.save(bot.bot(), shard, position);
          }
          reconnect("invalid session");
        }
        case 0 -> {
          Event event = codec.normalize(bot.bot(), payload);
          if (!receiver.test(event)) throw new IllegalStateException("Queue rejected event");
          String session = position.session();
          if ("READY".equals(payload.get("t")))
            session = QqJson.string(QqJson.object(payload.get("d")).get("session_id"));
          long seq = QqJson.number(payload.get("s"), position.sequence());
          var accepted = new GatewayStore.Position(session, seq);
          if (durable) store.save(bot.bot(), shard, accepted);
          position = accepted;
          if (Set.of("READY", "RESUMED").contains(QqJson.string(payload.get("t"))))
            connected = true;
        }
        default -> LOG.debug("Gateway control opcode {}", op);
      }
    }

    void beat() {
      Map<String, Object> frame = new LinkedHashMap<>();
      frame.put("op", 1);
      frame.put("d", position.sequence() < 0 ? null : position.sequence());
      awaitingAck = true;
      send(frame);
    }

    synchronized void send(Map<String, Object> frame) {
      WebSocket ws = socket;
      if (ws != null)
        sending =
            sending
                .handle((ok, error) -> null)
                .thenCompose(
                    ok -> ws.sendText(QqJson.write(frame), true).thenApply(sent -> (Void) null))
                .whenComplete(
                    (ok, error) -> {
                      if (error != null && ws == socket) reconnect("send failed");
                    });
    }

    void reconnect(String reason) {
      connected = false;
      WebSocket ws = socket;
      socket = null;
      if (ws != null) ws.abort();
      if (heartbeat != null) heartbeat.cancel(false);
      if (running.get() && !fatal && reconnecting.compareAndSet(false, true))
        clock.schedule(() -> work.submit(this::connect), 5, TimeUnit.SECONDS);
    }

    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      if (ws != socket) return CompletableFuture.completedFuture(null);
      if (Set.of(4004, 4010, 4011, 4013, 4014).contains(status)) {
        fatal = true;
        LOG.error(
            "Gateway stopped for bot {} shard {}, platform close code {}; check credentials,"
                + " intents and shard configuration",
            bot.bot(),
            shard,
            status);
      }
      reconnect("closed");
      return CompletableFuture.completedFuture(null);
    }

    public void onError(WebSocket ws, Throwable error) {
      if (ws != socket) return;
      reconnect("transport error");
    }
  }

  public boolean healthy() {
    return connections.stream().allMatch(c -> c.connected && !c.fatal);
  }

  public void close() {
    running.set(false);
    connections.forEach(
        c -> {
          if (c.heartbeat != null) c.heartbeat.cancel(false);
          if (c.socket != null) c.socket.abort();
        });
    clock.shutdownNow();
    work.shutdownNow();
  }
}
