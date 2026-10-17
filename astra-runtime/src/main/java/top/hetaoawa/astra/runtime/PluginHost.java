package top.hetaoawa.astra.runtime;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.pf4j.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.api.annotation.Scheduled;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

public final class PluginHost implements AutoCloseable {
  private final AstraPluginManager manager;
  private final HandlerRegistry registry;
  private final ContextFactory contexts;
  private final List<BotId> bots;
  private final SessionManager sessions;
  private final TaskManager tasks;
  private final OutboundQueue outbound;
  private final JdbcDatabase db;
  private final Map<BotId, ServiceRegistry> services;
  private final Map<String, AstraPlugin> plugins = new LinkedHashMap<>();
  private final Set<String> active = new HashSet<>();

  public PluginHost(
      AstraPluginManager manager,
      HandlerRegistry registry,
      ContextFactory contexts,
      List<BotId> bots,
      SessionManager sessions,
      TaskManager tasks,
      OutboundQueue outbound,
      JdbcDatabase db,
      Map<BotId, ServiceRegistry> services,
      boolean enabledByDefault) {
    this.manager = manager;
    this.registry = registry;
    this.contexts = contexts;
    this.bots = List.copyOf(bots);
    this.sessions = sessions;
    this.tasks = tasks;
    this.outbound = outbound;
    this.db = db;
    this.services = services;
    try {
      manager.loadPlugins();
      manager.startPlugins();
      for (var wrapper : manager.getResolvedPlugins()) {
        if (wrapper.getPluginState() != PluginState.STARTED)
          throw new IllegalStateException("Plugin failed to start: " + wrapper.getPluginId());
        var plugin = ((AstraPluginManager.BridgePlugin) wrapper.getPlugin()).delegate();
        plugins.put(wrapper.getPluginId(), plugin);
        registry.bind(wrapper.getPluginId(), plugin);
        registry.register(registry.scan(wrapper.getPluginId(), plugin));
        for (var bot : bots) registry.enabled(wrapper.getPluginId(), bot, false);
      }
      // PF4J's resolved order respects declared dependencies.
      for (String id : plugins.keySet())
        for (var bot : bots) {
          boolean enabled =
              db
                  .jdbc
                  .query(
                      "SELECT enabled_flag FROM astra_plugin_state WHERE plugin_id=? AND bot_key=?",
                      (r, n) -> r.getInt(1) != 0,
                      id,
                      bot.key())
                  .stream()
                  .findFirst()
                  .orElse(enabledByDefault);
          if (enabled) enable(id, bot, false);
        }
    } catch (RuntimeException | Error error) {
      try {
        close();
      } catch (Throwable cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }

  public synchronized List<Map<String, Object>> list() {
    return plugins.keySet().stream()
        .map(
            id ->
                Map.<String, Object>of(
                    "id",
                    id,
                    "version",
                    manager.getPlugin(id).getDescriptor().getVersion(),
                    "bots",
                    bots.stream()
                        .filter(b -> active.contains(key(id, b)))
                        .map(b -> b.appId() + "/" + b.environment())
                        .toList()))
        .toList();
  }

  public synchronized void setEnabled(String id, BotId bot, boolean enabled) {
    if (bot != null && !bots.contains(bot)) throw new IllegalArgumentException("Unknown bot");
    if (bot == null) {
      for (var b : bots)
        if (enabled) enable(id, b, true);
        else disable(id, b, true);
    } else if (enabled) enable(id, bot, true);
    else disable(id, bot, true);
  }

  private void enable(String id, BotId bot, boolean persist) {
    var plugin = require(id);
    String key = key(id, bot);
    if (active.contains(key)) return;
    for (var dependency : manager.getPlugin(id).getDescriptor().getDependencies())
      if (!dependency.isOptional() && !active.contains(key(dependency.getPluginId(), bot)))
        throw new IllegalStateException("Enable dependency first: " + dependency.getPluginId());
    registry.beginInitialization(id, bot);
    PluginContext context = null;
    try {
      context = contexts.create(id, bot);
      plugin.onEnable(context);
      registerScheduled(id, bot, plugin, context);
      if (persist) persist(id, bot, true);
      registry.commitInitialization(id, bot);
      active.add(key);
    } catch (Throwable e) {
      registry.enabled(id, bot, false);
      active.remove(key);
      tasks.remove(id, bot);
      sessions.remove(id, bot);
      outbound.cancel(id, bot);
      services.get(bot).remove(id);
      try {
        if (context != null) plugin.onDisable(context);
      } catch (Throwable ignored) {
      }
      if (persist)
        try {
          persist(id, bot, false);
        } catch (Throwable persistence) {
          e.addSuppressed(persistence);
        }
      throw new IllegalStateException("Plugin enable failed: " + id, e);
    }
  }

  private void disable(String id, BotId bot, boolean persist) {
    var plugin = require(id);
    for (String other : List.copyOf(plugins.keySet()))
      if (!other.equals(id)
          && active.contains(key(other, bot))
          && manager.getPlugin(other).getDescriptor().getDependencies().stream()
              .anyMatch(d -> !d.isOptional() && d.getPluginId().equals(id)))
        disable(other, bot, persist);
    registry.enabled(id, bot, false);
    tasks.remove(id, bot);
    sessions.remove(id, bot);
    outbound.cancel(id, bot);
    registry.drain(id, bot);
    if (active.remove(key(id, bot))) {
      try {
        plugin.onDisable(contexts.create(id, bot));
      } catch (Throwable e) {
        org.slf4j.LoggerFactory.getLogger(PluginHost.class)
            .warn("Plugin disable callback failed: {}", id, e);
      } finally {
        services.get(bot).remove(id);
      }
    }
    if (persist) persist(id, bot, false);
  }

  private AstraPlugin require(String id) {
    var plugin = plugins.get(id);
    if (plugin == null) throw new IllegalArgumentException("Unknown plugin: " + id);
    return plugin;
  }

  private static String key(String id, BotId bot) {
    return Keys.join(id, bot.key());
  }

  private void persist(String id, BotId bot, boolean enabled) {
    db.transaction.executeWithoutResult(
        tx -> {
          if (db.jdbc.update(
                  "UPDATE astra_plugin_state SET enabled_flag=? WHERE plugin_id=? AND bot_key=?",
                  enabled ? 1 : 0,
                  id,
                  bot.key())
              == 0)
            db.jdbc.update(
                "INSERT INTO astra_plugin_state(plugin_id,bot_key,enabled_flag) VALUES(?,?,?)",
                id,
                bot.key(),
                enabled ? 1 : 0);
        });
  }

  private void registerScheduled(String id, BotId bot, AstraPlugin plugin, PluginContext context) {
    for (Object target : plugin.handlers())
      for (Method method : target.getClass().getMethods()) {
        Scheduled a = method.getAnnotation(Scheduled.class);
        if (a == null) continue;
        if (method.getParameterCount() > 1
            || method.getParameterCount() == 1
                && method.getParameterTypes()[0] != PluginContext.class)
          throw new IllegalArgumentException("Scheduled methods accept only PluginContext");
        int modes =
            (a.delayMillis() >= 0 ? 1 : 0)
                + (a.intervalMillis() > 0 ? 1 : 0)
                + (!a.cron().isBlank() ? 1 : 0);
        if (modes != 1) throw new IllegalArgumentException("Exactly one scheduled mode required");
        Runnable task =
            () -> {
              try (var lease = registry.acquire(id, bot)) {
                if (lease == null) return;
                try {
                  Object result =
                      method.invoke(
                          target,
                          method.getParameterCount() == 0 ? new Object[0] : new Object[] {context});
                  if (result instanceof CompletionStage<?> stage) stage.toCompletableFuture().get();
                } catch (Throwable e) {
                  Throwable cause =
                      e instanceof InvocationTargetException || e instanceof ExecutionException
                          ? e.getCause()
                          : e;
                  plugin.onError(
                      new HandlerFailure(
                          id,
                          method.toGenericString(),
                          null,
                          cause,
                          e instanceof InterruptedException),
                      context);
                }
              }
            };
        if (a.delayMillis() >= 0) context.tasks().delay(Duration.ofMillis(a.delayMillis()), task);
        else if (a.intervalMillis() > 0)
          context
              .tasks()
              .interval(
                  Duration.ofMillis(a.intervalMillis()),
                  Duration.ofMillis(a.intervalMillis()),
                  task);
        else context.tasks().cron(a.cron(), ZoneId.of(a.zone()), task);
      }
  }

  public synchronized void close() {
    for (String id : List.copyOf(plugins.keySet())) for (var bot : bots) disable(id, bot, false);
    manager.stopPlugins();
    manager.unloadPlugins();
    plugins.keySet().forEach(registry::remove);
  }
}
