package top.hetaoawa.astra.storage;

import io.lettuce.core.*;
import io.lettuce.core.api.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class RedisNamespaceLease implements AutoCloseable {
  static final class InUse extends IllegalStateException {
    InUse() {
      super("Redis namespace is owned by another Astra runtime; configure a separate namespace");
    }
  }

  private final RedisClient client;
  private StatefulRedisConnection<String, String> connection;
  private final String key, owner = UUID.randomUUID().toString();
  private final AtomicBoolean valid = new AtomicBoolean(true), fenced = new AtomicBoolean(false);
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

  RedisNamespaceLease(String uri, String prefix) {
    key = prefix + ":runtime-owner";
    client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .autoReconnect(true)
            .build());
    try {
      connection = client.connect();
      connection.setTimeout(Duration.ofSeconds(3));
      if (connection.sync().set(key, owner, SetArgs.Builder.nx().px(45000)) == null)
        throw new InUse();
      clock.scheduleWithFixedDelay(this::renew, 5, 5, TimeUnit.SECONDS);
    } catch (Throwable error) {
      close();
      throw error;
    }
  }

  private void renew() {
    if (fenced.get()) return;
    try {
      Long result =
          connection
              .sync()
              .eval(
                  "local v=redis.call('GET',KEYS[1]);if v==ARGV[1] then"
                      + " redis.call('PEXPIRE',KEYS[1],45000);return 1 elseif not v then"
                      + " redis.call('SET',KEYS[1],ARGV[1],'PX',45000);return 1 else return 0 end",
                  ScriptOutputType.INTEGER,
                  new String[] {key},
                  owner);
      valid.set(result != null && result == 1);
      if (!valid.get()) fenced.set(true);
    } catch (RedisException error) {
      valid.set(false);
    }
  }

  boolean valid() {
    return valid.get() && !fenced.get();
  }

  public void close() {
    clock.shutdownNow();
    valid.set(false);
    if (connection != null) {
      try {
        connection
            .sync()
            .eval(
                "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else"
                    + " return 0 end",
                ScriptOutputType.INTEGER,
                new String[] {key},
                owner);
      } catch (RedisException ignored) {
      }
      connection.close();
    }
    client.shutdown();
  }
}
