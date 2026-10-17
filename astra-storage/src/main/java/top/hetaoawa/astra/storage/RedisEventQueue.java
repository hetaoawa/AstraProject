package top.hetaoawa.astra.storage;

import io.lettuce.core.*;
import io.lettuce.core.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class RedisEventQueue implements EventQueue {
  private final RedisClient client;
  private StatefulRedisConnection<String, String> writer;
  private final Map<BotId, StatefulRedisConnection<String, String>> readers = new HashMap<>();
  private final Map<BotId, Integer> capacities = new HashMap<>();
  private final String prefix;
  private volatile boolean closed;
  private final Set<BotId> recovered = ConcurrentHashMap.newKeySet();
  private final Map<BotId, String> cursors = new ConcurrentHashMap<>();
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  public RedisEventQueue(String uri, String prefix, List<BotPolicy> bots) {
    this.prefix = prefix;
    client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .autoReconnect(true)
            .build());
    try {
      writer = client.connect();
      writer.setTimeout(Duration.ofSeconds(3));
      writer.sync().ping();
      for (var b : bots) {
        capacities.put(b.bot(), b.queueCapacity());
        var c = client.connect();
        c.setTimeout(Duration.ofSeconds(3));
        readers.put(b.bot(), c);
        try {
          writer
              .sync()
              .xgroupCreate(
                  XReadArgs.StreamOffset.from(key(b.bot()), "0-0"),
                  "astra",
                  XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
          if (!e.getMessage().contains("BUSYGROUP")) throw e;
        }
      }
    } catch (Throwable error) {
      close();
      throw error;
    }
  }

  private String key(BotId bot) {
    return prefix + ":events:" + bot.key();
  }

  private String flight(BotId bot, String id) {
    return Keys.join(bot.key(), id);
  }

  public boolean offer(BotId bot, String payload) {
    if (closed || !capacities.containsKey(bot)) return false;
    try {
      Long r =
          writer
              .sync()
              .eval(
                  "if redis.call('XLEN',KEYS[1]) >= tonumber(ARGV[1]) then return 0 end;"
                      + " redis.call('XADD',KEYS[1],'*','payload',ARGV[2]); return 1",
                  ScriptOutputType.INTEGER,
                  new String[] {key(bot)},
                  capacities.get(bot).toString(),
                  payload);
      return r != null && r == 1;
    } catch (RedisException e) {
      return false;
    }
  }

  public Delivery poll(BotId bot, Duration timeout) throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    try {
      return pollOnce(bot, timeout);
    } catch (RedisException error) {
      recovered.remove(bot);
      cursors.remove(bot);
      throw error;
    }
  }

  private Delivery pollOnce(BotId bot, Duration timeout) {
    var c = readers.get(bot).sync();
    List<StreamMessage<String, String>> rows;
    while (!recovered.contains(bot)) {
      rows =
          c.xreadgroup(
              Consumer.from("astra", "single-runtime"),
              XReadArgs.Builder.count(1),
              XReadArgs.StreamOffset.from(key(bot), cursors.getOrDefault(bot, "0-0")));
      if (rows == null || rows.isEmpty()) {
        recovered.add(bot);
        break;
      }
      var d = rows.getFirst();
      cursors.put(bot, d.getId());
      if (d.getBody() == null || !d.getBody().containsKey("payload"))
        throw new IllegalStateException("Pending payload removed externally");
      if (inFlight.add(flight(bot, d.getId())))
        return new Delivery(bot, d.getId(), d.getBody().get("payload"));
    }
    rows =
        c.xreadgroup(
            Consumer.from("astra", "single-runtime"),
            XReadArgs.Builder.block(timeout).count(1),
            XReadArgs.StreamOffset.lastConsumed(key(bot)));
    if (rows == null || rows.isEmpty()) return null;
    var d = rows.getFirst();
    if (d.getBody() == null || !d.getBody().containsKey("payload"))
      throw new IllegalStateException("Queued payload missing");
    inFlight.add(flight(bot, d.getId()));
    return new Delivery(bot, d.getId(), d.getBody().get("payload"));
  }

  public void acknowledge(Delivery d) {
    writer
        .sync()
        .eval(
            "redis.call('XACK',KEYS[1],ARGV[1],ARGV[2]);redis.call('XDEL',KEYS[1],ARGV[2]);return"
                + " 1",
            ScriptOutputType.INTEGER,
            new String[] {key(d.bot())},
            "astra",
            d.id());
    inFlight.remove(flight(d.bot(), d.id()));
  }

  public boolean healthy() {
    if (closed) return false;
    try {
      return "PONG".equals(writer.sync().ping());
    } catch (RedisException e) {
      return false;
    }
  }

  public String backend() {
    return "redis";
  }

  public synchronized void close() {
    if (closed) return;
    closed = true;
    readers.values().forEach(StatefulRedisConnection::close);
    if (writer != null) writer.close();
    client.shutdown();
  }
}
