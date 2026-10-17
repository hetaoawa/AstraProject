package top.hetaoawa.astra.runtime;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

final class RuntimeContext implements PluginContext {
  private final String owner;
  private final BotId bot;
  private final HandlerRegistry registry;
  private final long generation;
  private final Messaging messaging;
  private final PlatformApi api;
  private final Directory directory;
  private final History history;
  private EventArchive archive;
  private PluginRouteTable routes;
  private final Sessions sessions;
  private final Tasks tasks;
  private final Services services;
  private final Map<String, Object> configuration;
  private final java.util.concurrent.ConcurrentMap<String, DeferredHandle> deferredSessions =
      new java.util.concurrent.ConcurrentHashMap<>();

  RuntimeContext(
      String owner,
      BotId bot,
      HandlerRegistry registry,
      OutboundQueue outbound,
      OutboundStore outboundStore,
      JdbcDirectory directory,
      JdbcHistory history,
      SessionManager sessions,
      TaskManager tasks,
      ServiceRegistry services,
      Map<String, Object> configuration) {
    this.owner = owner;
    this.bot = bot;
    this.registry = registry;
    this.generation = registry.generation(owner, bot);
    this.messaging = new QqMessaging(owner, bot, outbound, outboundStore);
    this.api = outbound.owned(owner, bot);
    this.directory = new ScopedDirectory(directory, owner, bot);
    this.history = new ScopedHistory(history, bot);
    this.sessions = sessions.owned(owner, this);
    this.tasks = tasks.owned(owner, bot);
    this.services = services.owned(owner);
    this.configuration = Map.copyOf(configuration);
  }

  RuntimeContext(
      String owner,
      BotId bot,
      HandlerRegistry registry,
      OutboundQueue outbound,
      OutboundStore outboundStore,
      JdbcDirectory directory,
      JdbcHistory history,
      SessionManager sessions,
      TaskManager tasks,
      ServiceRegistry services,
      Map<String, Object> configuration,
      JdbcEventArchive archive) {
    this(
        owner,
        bot,
        registry,
        outbound,
        outboundStore,
        directory,
        history,
        sessions,
        tasks,
        services,
        configuration);
    this.archive = archive.scoped(bot);
  }

  public EventArchive eventArchive() {
    if (archive == null) throw new EventArchiveDisabledException();
    return archive;
  }

  RuntimeContext(
      String owner,
      BotId bot,
      HandlerRegistry registry,
      OutboundQueue outbound,
      OutboundStore outboundStore,
      JdbcDirectory directory,
      JdbcHistory history,
      SessionManager sessions,
      TaskManager tasks,
      ServiceRegistry services,
      Map<String, Object> configuration,
      JdbcEventArchive archive,
      PluginRouteTable routes) {
    this(
        owner,
        bot,
        registry,
        outbound,
        outboundStore,
        directory,
        history,
        sessions,
        tasks,
        services,
        configuration,
        archive);
    this.routes = routes;
  }

  public HttpRoutes httpRoutes() {
    return (method, path, handler) -> {
      requireEnabled();
      if (routes == null) throw new IllegalStateException("HTTP listener unavailable");
      PluginRouteTable.validate(method, path);
      Objects.requireNonNull(handler);
      var pending = new DeferredHandle();
      registry.registerResource(
          owner,
          bot,
          generation,
          () -> {
            if (pending.cancelled()) return;
            var route = routes.register(owner, bot, method, path, handler);
            pending.install(route::close);
            registry.trackCleanup(owner, bot, generation, route::close);
          });
      return pending;
    };
  }

  private void requireEnabled() {
    if (!registry.usable(owner, bot, generation))
      throw new IllegalStateException("Plugin is disabled for this bot");
  }

  public String pluginId() {
    return owner;
  }

  public BotId bot() {
    return bot;
  }

  public Directory directory() {
    return directory;
  }

  public History history() {
    return history;
  }

  public Map<String, Object> configuration() {
    return configuration;
  }

  public Messaging messaging() {
    return new Messaging() {
      public CompletionStage<ApiResponse> send(ChatRef chat, Map<String, Object> body) {
        requireEnabled();
        return messaging.send(chat, body);
      }

      public CompletionStage<ApiResponse> reply(MessageEvent source, Map<String, Object> body) {
        requireEnabled();
        return messaging.reply(source, body);
      }

      public CompletionStage<ApiResponse> recall(ChatRef chat, String id) {
        requireEnabled();
        return messaging.recall(chat, id);
      }
    };
  }

  public PlatformApi api() {
    return new PlatformApi() {
      public Map<String, ApiOperation> operations() {
        return api.operations();
      }

      public CompletionStage<ApiResponse> call(
          String operation,
          Map<String, String> path,
          Map<String, Object> query,
          Map<String, Object> body) {
        requireEnabled();
        return api.call(operation, path, query, body);
      }

      public CompletionStage<ApiResponse> raw(
          String method, String path, Map<String, Object> query, Map<String, Object> body) {
        requireEnabled();
        return api.raw(method, path, query, body);
      }

      public CompletionStage<ApiResponse> uploadPart(
          String url, byte[] bytes, Map<String, String> headers) {
        requireEnabled();
        return api.uploadPart(url, bytes, headers);
      }

      public CompletionStage<ApiResponse> multipart(
          String method, String path, Map<String, String> fields, Map<String, UploadFile> files) {
        requireEnabled();
        return api.multipart(method, path, fields, files);
      }
    };
  }

  public Sessions sessions() {
    return new Sessions() {
      public Handle await(
          ChatRef chat,
          Identity user,
          Predicate<MessageEvent> filter,
          Duration timeout,
          ReplyHandler reply,
          Runnable expired) {
        requireEnabled();
        if (!bot.equals(chat.bot())
            || !bot.equals(user.bot())
            || timeout.isNegative()
            || timeout.isZero())
          throw new IllegalArgumentException("Invalid session scope or timeout");
        Objects.requireNonNull(filter);
        Objects.requireNonNull(reply);
        var handle = new DeferredHandle();
        String key = Keys.join(chat.key(), user.key());
        while (true) {
          var previous = deferredSessions.putIfAbsent(key, handle);
          if (previous == null) break;
          if (!previous.cancelled())
            throw new IllegalStateException("Another session already owns this conversation");
          deferredSessions.remove(key, previous);
        }
        registry.registerResource(
            owner,
            bot,
            generation,
            () ->
                handle.activate(
                    () -> {
                      deferredSessions.remove(key, handle);
                      return sessions.await(
                              chat,
                              user,
                              filter,
                              timeout,
                              reply,
                              expired == null ? null : guarded("session.expired", expired))
                          ::cancel;
                    }));
        return handle;
      }

      public void cancel(ChatRef chat, Identity user) {
        requireEnabled();
        registry.mutateResource(
            owner,
            bot,
            generation,
            () -> {
              var pending = deferredSessions.remove(Keys.join(chat.key(), user.key()));
              if (pending != null) pending.cancel();
              sessions.cancel(chat, user);
            });
      }
    };
  }

  private Runnable guarded(String id, Runnable task) {
    return () -> {
      if (!registry.awaitActivation(owner, bot, generation)) return;
      try (var lease = registry.acquire(owner, bot)) {
        if (lease != null)
          try {
            task.run();
          } catch (Throwable e) {
            registry
                .plugin(owner)
                .ifPresent(
                    p ->
                        p.onError(
                            new HandlerFailure(
                                owner, id, null, e, e instanceof InterruptedException),
                            this));
          }
      }
    };
  }

  public Tasks tasks() {
    return new Tasks() {
      public Handle delay(Duration delay, Runnable task) {
        requireEnabled();
        if (delay.isNegative()) throw new IllegalArgumentException("Negative delay");
        Objects.requireNonNull(task);
        var handle = new DeferredHandle();
        registry.registerResource(
            owner,
            bot,
            generation,
            () -> handle.activate(() -> tasks.delay(delay, guarded("task.delay", task))::cancel));
        return handle;
      }

      public Handle interval(Duration initial, Duration period, Runnable task) {
        requireEnabled();
        if (initial.isNegative() || period.toMillis() < 1)
          throw new IllegalArgumentException("Invalid interval");
        Objects.requireNonNull(task);
        var handle = new DeferredHandle();
        registry.registerResource(
            owner,
            bot,
            generation,
            () ->
                handle.activate(
                    () -> tasks.interval(initial, period, guarded("task.interval", task))::cancel));
        return handle;
      }

      public Handle cron(String expression, ZoneId zone, Runnable task) {
        requireEnabled();
        org.springframework.scheduling.support.CronExpression.parse(expression);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(task);
        var handle = new DeferredHandle();
        registry.registerResource(
            owner,
            bot,
            generation,
            () ->
                handle.activate(
                    () -> tasks.cron(expression, zone, guarded("task.cron", task))::cancel));
        return handle;
      }
    };
  }

  public Services services() {
    return new Services() {
      public <T> void register(Class<T> type, T service) {
        requireEnabled();
        registry.mutateResource(owner, bot, generation, () -> services.register(type, service));
      }

      public <T> Optional<T> find(Class<T> type) {
        return services.find(type);
      }

      public void registerPermission(PermissionNode node) {
        requireEnabled();
        registry.mutateResource(owner, bot, generation, () -> services.registerPermission(node));
      }

      public List<PermissionNode> permissions() {
        return services.permissions();
      }
    };
  }

  private static final class DeferredHandle
      implements Tasks.Handle, Sessions.Handle, HttpRoutes.Handle {
    private boolean cancelled;
    private Runnable delegate;

    synchronized boolean cancelled() {
      return cancelled;
    }

    synchronized void activate(java.util.function.Supplier<Runnable> registration) {
      if (!cancelled) delegate = registration.get();
    }

    synchronized void install(Runnable cancel) {
      if (cancelled) cancel.run();
      else delegate = cancel;
    }

    public synchronized void cancel() {
      cancelled = true;
      if (delegate != null) delegate.run();
    }

    public void close() {
      cancel();
    }
  }
}
