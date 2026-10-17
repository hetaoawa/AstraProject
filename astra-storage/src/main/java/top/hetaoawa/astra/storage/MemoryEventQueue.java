package top.hetaoawa.astra.storage;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class MemoryEventQueue implements EventQueue {
  private final Map<BotId, BlockingQueue<Delivery>> queues = new ConcurrentHashMap<>();
  private final Map<BotId, Semaphore> slots = new ConcurrentHashMap<>();
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  public MemoryEventQueue(List<BotPolicy> bots) {
    for (var b : bots) {
      queues.put(b.bot(), new LinkedBlockingQueue<>());
      slots.put(b.bot(), new Semaphore(b.queueCapacity()));
    }
  }

  public boolean offer(BotId bot, String payload) {
    if (closed.get() || !slots.containsKey(bot) || !slots.get(bot).tryAcquire()) return false;
    return queues.get(bot).offer(new Delivery(bot, UUID.randomUUID().toString(), payload));
  }

  public Delivery poll(BotId bot, Duration timeout) throws InterruptedException {
    var d = queues.get(bot).poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (d != null) active.add(d.id());
    return d;
  }

  public void acknowledge(Delivery d) {
    if (active.remove(d.id())) slots.get(d.bot()).release();
  }

  public boolean healthy() {
    return !closed.get();
  }

  public String backend() {
    return "memory";
  }

  public void close() {
    closed.set(true);
    queues.values().forEach(Queue::clear);
    active.clear();
  }
}
