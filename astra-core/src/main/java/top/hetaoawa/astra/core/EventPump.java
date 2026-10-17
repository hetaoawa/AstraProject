package top.hetaoawa.astra.core;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.*;
import top.hetaoawa.astra.api.*;

public final class EventPump implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(EventPump.class);
  private final EventQueue queue;
  private final EventCodec codec;
  private final Dispatcher dispatcher;
  private final ExecutorService consumers = Executors.newVirtualThreadPerTaskExecutor(),
      workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentMap<String, CompletableFuture<Void>> lanes = new ConcurrentHashMap<>();
  private final Set<BotId> poisoned = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean receiving = new AtomicBoolean(true),
      processing = new AtomicBoolean(true);

  public EventPump(
      EventQueue queue, EventCodec codec, Dispatcher dispatcher, List<BotPolicy> bots) {
    this.queue = queue;
    this.codec = codec;
    this.dispatcher = dispatcher;
    for (var bot : bots) {
      Semaphore slots = new Semaphore(bot.concurrency(), true);
      consumers.submit(() -> consume(bot.bot(), slots));
    }
  }

  private void consume(BotId bot, Semaphore slots) {
    while (receiving.get()) {
      try {
        var delivery = queue.poll(bot, Duration.ofSeconds(1));
        if (delivery == null) continue;
        Event event;
        try {
          event = codec.decode(delivery.payload());
          if (!bot.equals(event.bot())) throw new IllegalArgumentException("Queued bot mismatch");
        } catch (Throwable malformed) {
          poisoned.add(bot);
          LOG.error(
              "Invalid queued event retained; bot consumer stopped: {}", delivery.id(), malformed);
          return;
        }
        String lane = event.chat().key();
        var next =
            lanes.compute(
                lane,
                (key, previous) -> {
                  var before =
                      previous == null ? CompletableFuture.<Void>completedFuture(null) : previous;
                  return before
                      .handle((ok, error) -> null)
                      .thenRunAsync(
                          () -> {
                            boolean acquired = false;
                            try {
                              slots.acquire();
                              acquired = true;
                              process(delivery, event);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            } finally {
                              if (acquired) slots.release();
                            }
                          },
                          workers);
                });
        next.whenComplete((ok, error) -> lanes.remove(lane, next));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable e) {
        LOG.warn("Inbound backend unavailable; waiting on the selected backend");
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void process(EventQueue.Delivery delivery, Event event) {
    boolean dispatched = false;
    while (processing.get()) {
      try {
        if (!dispatched) {
          dispatcher.dispatch(event);
          dispatched = true;
        }
        queue.acknowledge(delivery);
        return;
      } catch (Throwable e) {
        LOG.warn(
            "Infrastructure operation failed; durable ledger prevents business replay: {}",
            event.key(),
            e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  public boolean accept(Event event) {
    return receiving.get()
        && !poisoned.contains(event.bot())
        && queue.offer(event.bot(), codec.encode(event));
  }

  public int inFlight() {
    return lanes.size();
  }

  public boolean healthy() {
    return poisoned.isEmpty() && queue.healthy();
  }

  public void close() {
    receiving.set(false);
    consumers.shutdownNow();
    try {
      consumers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    workers.shutdown();
    try {
      if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
        processing.set(false);
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      processing.set(false);
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
    processing.set(false);
    queue.close();
  }
}
