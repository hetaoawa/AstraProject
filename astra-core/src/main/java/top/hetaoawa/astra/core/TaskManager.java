package top.hetaoawa.astra.core;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.scheduling.support.CronExpression;
import top.hetaoawa.astra.api.*;

public final class TaskManager implements AutoCloseable {
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Managed> tasks = ConcurrentHashMap.newKeySet();

  private final class Managed implements Tasks.Handle {
    final String owner;
    final BotId bot;
    final AtomicBoolean cancelled = new AtomicBoolean(), running = new AtomicBoolean();
    volatile ScheduledFuture<?> scheduled;
    volatile Future<?> active;

    Managed(String owner, BotId bot) {
      this.owner = owner;
      this.bot = bot;
    }

    public void cancel() {
      cancelled.set(true);
      if (scheduled != null) scheduled.cancel(false);
      if (active != null) active.cancel(true);
      tasks.remove(this);
    }

    void run(Runnable task, boolean once) {
      if (cancelled.get() || !running.compareAndSet(false, true)) return;
      active =
          workers.submit(
              () -> {
                try {
                  if (!cancelled.get()) task.run();
                } catch (Throwable e) {
                  org.slf4j.LoggerFactory.getLogger(TaskManager.class)
                      .warn("Scheduled task failed; no retry", e);
                } finally {
                  running.set(false);
                  if (once) tasks.remove(this);
                }
              });
    }
  }

  public Tasks owned(String owner, BotId bot) {
    return new Tasks() {
      private Managed add() {
        Managed m = new Managed(owner, bot);
        tasks.add(m);
        return m;
      }

      public Handle delay(Duration delay, Runnable task) {
        if (delay.isNegative()) throw new IllegalArgumentException("Negative delay");
        Managed m = add();
        m.scheduled =
            clock.schedule(() -> m.run(task, true), delay.toMillis(), TimeUnit.MILLISECONDS);
        return m;
      }

      public Handle interval(Duration initial, Duration period, Runnable task) {
        if (initial.isNegative() || period.toMillis() < 1)
          throw new IllegalArgumentException("Invalid interval");
        Managed m = add();
        m.scheduled =
            clock.scheduleAtFixedRate(
                () -> m.run(task, false),
                initial.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS);
        return m;
      }

      public Handle cron(String expression, ZoneId zone, Runnable task) {
        CronExpression cron = CronExpression.parse(expression);
        Managed m = add();
        scheduleCron(m, cron, zone, task);
        return m;
      }
    };
  }

  private void scheduleCron(Managed m, CronExpression cron, ZoneId zone, Runnable task) {
    if (m.cancelled.get()) return;
    ZonedDateTime now = ZonedDateTime.now(zone), next = cron.next(now);
    if (next == null) {
      m.cancel();
      return;
    }
    m.scheduled =
        clock.schedule(
            () -> {
              m.run(task, false);
              scheduleCron(m, cron, zone, task);
            },
            Math.max(1, Duration.between(now, next).toMillis()),
            TimeUnit.MILLISECONDS);
  }

  public void remove(String owner, BotId bot) {
    tasks.stream()
        .filter(m -> m.owner.equals(owner) && (bot == null || m.bot.equals(bot)))
        .toList()
        .forEach(Managed::cancel);
  }

  public int count() {
    return tasks.size();
  }

  public void close() {
    tasks.forEach(Managed::cancel);
    clock.shutdownNow();
    workers.shutdownNow();
  }
}
