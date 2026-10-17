package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.storage.*;

class RedisAcceptanceTest {
  @TempDir Path temp;

  @Test
  void startupFallbackStaysMemory() {
    var bots = List.of(new BotPolicy(new BotId("bot", "production"), 1, 2, "", false));
    try (var pair = QueueFactory.select(true, "redis://127.0.0.1:1", "unreachable", bots, bots)) {
      assertEquals("memory", pair.inbound().backend());
      assertEquals("memory", pair.outbound().backend());
      assertTrue(pair.inbound().offer(bots.getFirst().bot(), "one"));
    }
  }

  @Test
  void realStreamsCapacityPendingRecoveryAndRuntimeDisconnect() throws Exception {
    String executable = System.getProperty("astra.test.redisExecutable");
    assumeTrue(
        executable != null,
        "Set astra.test.redisExecutable to a disposable Redis binary to run real Streams"
            + " acceptance");
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    List<String> command =
        List.of(
            executable,
            "--bind",
            "127.0.0.1",
            "--port",
            String.valueOf(port),
            "--dir",
            temp.toString(),
            "--appendonly",
            "yes",
            "--appendfsync",
            "always",
            "--save",
            "");
    Process process =
        new ProcessBuilder(command)
            .directory(temp.toFile())
            .redirectErrorStream(true)
            .redirectOutput(temp.resolve("redis.log").toFile())
            .start();
    var bot = new BotId("redis", "production");
    var policies = List.of(new BotPolicy(bot, 1, 2, "", false));
    String uri = "redis://127.0.0.1:" + port;
    RedisEventQueue queue = null;
    try {
      waitPort(port);
      queue = new RedisEventQueue(uri, "test:" + UUID.randomUUID(), policies);
      assertTrue(queue.offer(bot, "first"));
      assertTrue(queue.offer(bot, "second"));
      assertFalse(queue.offer(bot, "full"));
      var first = queue.poll(bot, Duration.ofMillis(50));
      assertEquals("first", first.payload());
      assertFalse(queue.offer(bot, "still-full"));
      process.destroy();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      assertFalse(queue.healthy());
      assertEquals("redis", queue.backend());
      assertFalse(queue.offer(bot, "outage"));
      process =
          new ProcessBuilder(command)
              .directory(temp.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(temp.resolve("redis.log").toFile()))
              .start();
      waitPort(port);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!queue.healthy() && System.nanoTime() < until) Thread.sleep(100);
      assertTrue(queue.healthy());
      queue.acknowledge(first);
      assertEquals("second", queue.poll(bot, Duration.ofMillis(50)).payload());
      // Leave second pending, close the runtime connection, then recover before any new entry.
      java.lang.reflect.Field field = RedisEventQueue.class.getDeclaredField("prefix");
      field.setAccessible(true);
      String prefix = (String) field.get(queue);
      queue.close();
      queue = new RedisEventQueue(uri, prefix, policies);
      assertTrue(queue.offer(bot, "new"));
      var recovered = queue.poll(bot, Duration.ofMillis(50));
      assertEquals("second", recovered.payload());
      queue.acknowledge(recovered);
      var fresh = queue.poll(bot, Duration.ofMillis(50));
      assertEquals("new", fresh.payload());
      queue.acknowledge(fresh);
    } finally {
      if (queue != null) queue.close();
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }
  }

  private void waitPort(int port) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      try (var socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
        return;
      } catch (java.io.IOException e) {
        Thread.sleep(100);
      }
    }
    fail("Redis did not start; inspect " + temp.resolve("redis.log"));
  }
}
