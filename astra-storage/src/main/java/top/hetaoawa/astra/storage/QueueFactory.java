package top.hetaoawa.astra.storage;

import java.time.Duration;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class QueueFactory {
  public record Pair(EventQueue inbound, EventQueue outbound, AutoCloseable lease)
      implements AutoCloseable {
    public void close() {
      inbound.close();
      outbound.close();
      try {
        lease.close();
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(QueueFactory.class)
            .warn("Queue namespace lease release failed");
      }
    }
  }

  private QueueFactory() {}

  private record Guarded(EventQueue delegate, RedisNamespaceLease lease) implements EventQueue {
    public boolean offer(BotId bot, String payload) {
      return lease.valid() && delegate.offer(bot, payload);
    }

    public Delivery poll(BotId bot, Duration timeout) throws InterruptedException {
      if (!lease.valid()) throw new IllegalStateException("Redis namespace lease unavailable");
      return delegate.poll(bot, timeout);
    }

    public void acknowledge(Delivery delivery) {
      if (!lease.valid()) throw new IllegalStateException("Redis namespace lease unavailable");
      delegate.acknowledge(delivery);
    }

    public boolean healthy() {
      return lease.valid() && delegate.healthy();
    }

    public String backend() {
      return delegate.backend();
    }

    public void close() {
      delegate.close();
    }
  }

  public static Pair select(
      boolean redis, String uri, String prefix, List<BotPolicy> inbound, List<BotPolicy> outbound) {
    if (redis) {
      EventQueue first = null, second = null;
      RedisNamespaceLease lease = null;
      try {
        lease = new RedisNamespaceLease(uri, prefix);
        first = new RedisEventQueue(uri, prefix + ":in", inbound);
        second = new RedisEventQueue(uri, prefix + ":out", outbound);
        return new Pair(new Guarded(first, lease), new Guarded(second, lease), lease);
      } catch (Exception error) {
        if (first != null) first.close();
        if (second != null) second.close();
        if (lease != null) lease.close();
        if (error instanceof RedisNamespaceLease.InUse) throw error;
        org.slf4j.LoggerFactory.getLogger(QueueFactory.class)
            .warn(
                "Redis unavailable at startup; selecting bounded memory queues for this entire"
                    + " run");
      }
    }
    return new Pair(new MemoryEventQueue(inbound), new MemoryEventQueue(outbound), () -> {});
  }
}
