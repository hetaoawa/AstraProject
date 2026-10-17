package top.hetaoawa.astra.core;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import top.hetaoawa.astra.api.*;

public final class SessionManager implements AutoCloseable {
  private final ConcurrentMap<String, Match> waiting = new ConcurrentHashMap<>();
  private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();

  public static final class Match {
    public final String id, owner;
    public final PluginContext context;
    final Predicate<MessageEvent> filter;
    final Sessions.ReplyHandler reply;
    final Runnable expired;
    volatile ScheduledFuture<?> timer;
    volatile Throwable filterError;

    Match(
        String owner,
        PluginContext context,
        Predicate<MessageEvent> filter,
        Sessions.ReplyHandler reply,
        Runnable expired) {
      this.id = "session." + UUID.randomUUID();
      this.owner = owner;
      this.context = context;
      this.filter = filter;
      this.reply = reply;
      this.expired = expired;
    }

    public CompletionStage<HandlerResult> invoke(MessageEvent e) throws Exception {
      if (filterError != null) throw new CompletionException(filterError);
      return reply.accept(e, context);
    }

    void cancel() {
      if (timer != null) timer.cancel(false);
    }
  }

  private static String key(ChatRef chat, Identity user) {
    return Keys.join(chat.key(), user.key());
  }

  public Sessions owned(String owner, PluginContext context) {
    return new Sessions() {
      public Handle await(
          ChatRef chat,
          Identity user,
          Predicate<MessageEvent> filter,
          Duration timeout,
          ReplyHandler reply,
          Runnable expired) {
        if (!context.bot().equals(chat.bot()) || !chat.bot().equals(user.bot()))
          throw new IllegalArgumentException("Session bot mismatch");
        if (timeout.isNegative() || timeout.isZero())
          throw new IllegalArgumentException("Positive timeout required");
        String key = key(chat, user);
        Match match =
            new Match(
                owner,
                context,
                Objects.requireNonNull(filter),
                Objects.requireNonNull(reply),
                expired);
        if (waiting.putIfAbsent(key, match) != null)
          throw new IllegalStateException("Another session already owns this conversation");
        match.timer =
            timers.schedule(
                () -> {
                  if (waiting.remove(key, match) && expired != null)
                    try {
                      expired.run();
                    } catch (Throwable e) {
                      org.slf4j.LoggerFactory.getLogger(SessionManager.class)
                          .warn("Session expiry callback failed: {}", owner, e);
                    }
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        return () -> {
          waiting.remove(key, match);
          match.cancel();
        };
      }

      public void cancel(ChatRef chat, Identity user) {
        if (!context.bot().equals(chat.bot()) || !chat.bot().equals(user.bot()))
          throw new IllegalArgumentException("Session bot mismatch");
        Match match = waiting.get(key(chat, user));
        if (match != null && match.owner.equals(owner) && waiting.remove(key(chat, user), match))
          match.cancel();
      }
    };
  }

  public Optional<Match> matching(MessageEvent e) {
    return matching(e, match -> true);
  }

  public Optional<Match> matching(MessageEvent e, Predicate<Match> available) {
    if (e.actor() == null) return Optional.empty();
    Match match = waiting.get(key(e.chat(), e.actor()));
    if (match == null || !available.test(match)) return Optional.empty();
    try {
      return match.filter.test(e) ? Optional.of(match) : Optional.empty();
    } catch (Throwable error) {
      match.filterError = error;
      return Optional.of(match);
    }
  }

  public boolean claim(MessageEvent e, Match match) {
    boolean claimed = waiting.remove(key(e.chat(), e.actor()), match);
    if (claimed) match.cancel();
    return claimed;
  }

  public void remove(String owner, BotId bot) {
    waiting
        .entrySet()
        .removeIf(
            e -> {
              Match m = e.getValue();
              if (m.owner.equals(owner) && (bot == null || m.context.bot().equals(bot))) {
                m.cancel();
                return true;
              }
              return false;
            });
  }

  public void close() {
    waiting.values().forEach(Match::cancel);
    waiting.clear();
    timers.shutdownNow();
  }
}
