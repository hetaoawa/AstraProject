package top.hetaoawa.astra.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.api.annotation.*;

public final class HandlerRegistry {
  private final CopyOnWriteArrayList<Handler> handlers = new CopyOnWriteArrayList<>();
  private final Set<String> disabled = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, AstraPlugin> plugins = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, java.util.concurrent.locks.ReentrantReadWriteLock> locks =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Object> lifecycleLocks = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> generations = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Initialization> initializing = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, List<Runnable>> cleanups = new ConcurrentHashMap<>();

  private static final class Initialization {
    final List<Runnable> registrations = new ArrayList<>();
    final CompletableFuture<Boolean> ready = new CompletableFuture<>();
  }

  public long generation(String owner, BotId bot) {
    return generations.getOrDefault(Keys.join(owner, bot.key()), 0L);
  }

  public void beginInitialization(String owner, BotId bot) {
    String key = Keys.join(owner, bot.key());
    synchronized (lifecycleLocks.computeIfAbsent(key, k -> new Object())) {
      disabled.add(key);
      generations.merge(key, 1L, Long::sum);
      var previous = initializing.put(key, new Initialization());
      if (previous != null) previous.ready.complete(false);
    }
  }

  public boolean usable(String owner, BotId bot, long generation) {
    String key = Keys.join(owner, bot.key());
    return generation(owner, bot) == generation
        && (enabled(owner, bot) || initializing.containsKey(key));
  }

  public void registerResource(String owner, BotId bot, long generation, Runnable registration) {
    String key = Keys.join(owner, bot.key());
    synchronized (lifecycleLocks.computeIfAbsent(key, k -> new Object())) {
      if (!usable(owner, bot, generation))
        throw new IllegalStateException("Plugin context is no longer active");
      var init = initializing.get(key);
      if (init != null) init.registrations.add(registration);
      else registration.run();
    }
  }

  public void mutateResource(String owner, BotId bot, long generation, Runnable mutation) {
    String key = Keys.join(owner, bot.key());
    synchronized (lifecycleLocks.computeIfAbsent(key, k -> new Object())) {
      if (!usable(owner, bot, generation))
        throw new IllegalStateException("Plugin context is no longer active");
      mutation.run();
    }
  }

  public void trackCleanup(String owner, BotId bot, long generation, Runnable cleanup) {
    mutateResource(
        owner,
        bot,
        generation,
        () ->
            cleanups
                .computeIfAbsent(Keys.join(owner, bot.key()), k -> new ArrayList<>())
                .add(cleanup));
  }

  public boolean awaitActivation(String owner, BotId bot, long generation) {
    var init = initializing.get(Keys.join(owner, bot.key()));
    if (init != null) {
      try {
        if (!init.ready.get()) return false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (ExecutionException e) {
        return false;
      }
    }
    return generation(owner, bot) == generation && enabled(owner, bot);
  }

  public void commitInitialization(String owner, BotId bot) {
    String key = Keys.join(owner, bot.key());
    synchronized (lifecycleLocks.computeIfAbsent(key, k -> new Object())) {
      var init = initializing.get(key);
      if (init == null) throw new IllegalStateException("Plugin is not initializing");
      for (var registration : init.registrations) registration.run();
      disabled.remove(key);
      init.ready.complete(true);
      initializing.remove(key, init);
    }
  }

  public interface Lease extends AutoCloseable {
    void close();
  }

  public void bind(String id, AstraPlugin plugin) {
    plugins.put(id, plugin);
  }

  public Optional<AstraPlugin> plugin(String id) {
    return Optional.ofNullable(plugins.get(id));
  }

  public Lease acquire(String owner, BotId bot) {
    String key = Keys.join(owner, bot.key());
    var lock =
        locks.computeIfAbsent(
            key, k -> new java.util.concurrent.locks.ReentrantReadWriteLock(true));
    lock.readLock().lock();
    if (!enabled(owner, bot)) {
      lock.readLock().unlock();
      return null;
    }
    return () -> lock.readLock().unlock();
  }

  public void drain(String owner, BotId bot) {
    var lock =
        locks.computeIfAbsent(
            Keys.join(owner, bot.key()),
            k -> new java.util.concurrent.locks.ReentrantReadWriteLock(true));
    lock.writeLock().lock();
    lock.writeLock().unlock();
  }

  private static final Set<Class<? extends Annotation>> TYPES =
      Set.of(
          OnMessage.class,
          OnNotice.class,
          OnRequest.class,
          OnMetaEvent.class,
          OnEvent.class,
          OnCommand.class,
          OnKeyword.class,
          OnFullMatch.class,
          OnRegex.class);

  public record Handler(
      String pluginId,
      String id,
      AstraPlugin plugin,
      Object target,
      Method method,
      Annotation annotation,
      int priority,
      boolean block,
      Scene[] scenes,
      Pattern regex) {
    public boolean matches(Event event) {
      if (scenes.length > 0 && Arrays.stream(scenes).noneMatch(s -> s == event.chat().scene()))
        return false;
      Class<? extends Annotation> type = annotation.annotationType();
      if (type == OnMessage.class && !(event instanceof MessageEvent)
          || type == OnNotice.class
              && !(event instanceof NoticeEvent || event instanceof MessageRecallEvent)
          || type == OnRequest.class && !(event instanceof RequestEvent)
          || type == OnMetaEvent.class && !(event instanceof MetaEvent)) return false;
      if (type == OnEvent.class
          || type == OnNotice.class
          || type == OnRequest.class
          || type == OnMetaEvent.class) {
        String[] values = (String[]) attribute(annotation, "types");
        return values.length == 0 || Arrays.asList(values).contains(event.data().platformType());
      }
      if (type == OnMessage.class) return true;
      if (!(event instanceof MessageEvent message)) return false;
      String text = matchText(message);
      if (type == OnCommand.class) {
        OnCommand command = (OnCommand) annotation;
        List<String> names = new ArrayList<>(List.of(command.value()));
        names.addAll(List.of(command.aliases()));
        for (String prefix : command.prefixes())
          for (String name : names) {
            String token = prefix + name;
            if (text.equals(token)
                || text.startsWith(token)
                    && text.length() > token.length()
                    && Character.isWhitespace(text.charAt(token.length()))) return true;
          }
        return false;
      }
      if (type == OnKeyword.class) return text.contains(((OnKeyword) annotation).value());
      if (type == OnFullMatch.class) return text.equals(((OnFullMatch) annotation).value());
      if (type == OnRegex.class) return regex.matcher(text).find();
      return false;
    }

    public CompletionStage<HandlerResult> invoke(Event event, PluginContext context)
        throws Exception {
      Object[] args =
          Arrays.stream(method.getParameterTypes())
              .map(
                  t ->
                      t == PluginContext.class
                          ? context
                          : t == CommandArgs.class ? commandArgs((MessageEvent) event) : event)
              .toArray();
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(target.getClass().getClassLoader());
      try {
        Object result = method.invoke(target, args);
        if (result instanceof CompletionStage<?> stage)
          return stage.thenApply(v -> v instanceof HandlerResult hr ? hr : HandlerResult.CONTINUE);
        return CompletableFuture.completedFuture(
            result instanceof HandlerResult hr ? hr : HandlerResult.CONTINUE);
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof Exception ex) throw ex;
        throw new RuntimeException(e.getCause());
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
    }

    private CommandArgs commandArgs(MessageEvent event) {
      OnCommand c = (OnCommand) annotation;
      String text = matchText(event);
      List<String> names = new ArrayList<>(List.of(c.value()));
      names.addAll(List.of(c.aliases()));
      for (String prefix : c.prefixes())
        for (String name : names) {
          String token = prefix + name;
          if (text.equals(token)
              || text.startsWith(token) && Character.isWhitespace(text.charAt(token.length()))) {
            String rest = text.substring(token.length()).strip();
            return new CommandArgs(
                name, rest, rest.isEmpty() ? List.of() : List.of(rest.split("\\s+")));
          }
        }
      throw new IllegalStateException("Command no longer matches");
    }
  }

  private static String matchText(MessageEvent event) {
    String text = event.message().text().strip();
    if (event.message().toMe() && event.message().raw().get("mentions") instanceof List<?> list) {
      for (Object value : list)
        if (value instanceof Map<?, ?> mention && Boolean.TRUE.equals(mention.get("bot"))) {
          String id = String.valueOf(mention.get("id"));
          for (String tag : List.of("<@!" + id + ">", "<@" + id + ">"))
            if (text.startsWith(tag)) text = text.substring(tag.length()).stripLeading();
        }
    }
    return text;
  }

  private static Object attribute(Annotation annotation, String name) {
    try {
      return annotation.annotationType().getMethod(name).invoke(annotation);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<Handler> scan(String pluginId, AstraPlugin plugin) {
    List<Handler> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Object target : plugin.handlers())
      for (Method method : target.getClass().getMethods()) {
        List<Annotation> annotations =
            Arrays.stream(method.getAnnotations())
                .filter(a -> TYPES.contains(a.annotationType()))
                .toList();
        if (annotations.isEmpty()) continue;
        if (annotations.size() != 1)
          throw new IllegalArgumentException("Use exactly one event annotation: " + method);
        Annotation annotation = annotations.getFirst();
        for (Class<?> parameter : method.getParameterTypes())
          if (parameter != PluginContext.class
              && !Event.class.isAssignableFrom(parameter)
              && !(parameter == CommandArgs.class && annotation instanceof OnCommand))
            throw new IllegalArgumentException("Unsupported handler parameter: " + method);
        Class<?> eventParameter =
            Arrays.stream(method.getParameterTypes())
                .filter(Event.class::isAssignableFrom)
                .findFirst()
                .orElse(Event.class);
        String id = Keys.join(pluginId, target.getClass().getName(), method.toGenericString());
        if (!ids.add(id)) throw new IllegalArgumentException("Duplicate handler: " + method);
        Annotation a = annotation;
        Handler h =
            new Handler(
                pluginId,
                id,
                plugin,
                target,
                method,
                a,
                (int) attribute(a, "priority"),
                (boolean) attribute(a, "block"),
                (Scene[]) attribute(a, "scenes"),
                a instanceof OnRegex re ? Pattern.compile(re.value()) : null);
        // A type-specific parameter narrows matching without exposing a cast failure.
        result.add(h);
      }
    return result;
  }

  public void register(List<Handler> scanned) {
    scanned.forEach(h -> bind(h.pluginId(), h.plugin()));
    handlers.addAll(scanned);
  }

  public void remove(String pluginId) {
    handlers.removeIf(h -> h.pluginId().equals(pluginId));
    plugins.remove(pluginId);
  }

  public void enabled(String pluginId, BotId bot, boolean enabled) {
    String key = Keys.join(pluginId, bot.key());
    synchronized (lifecycleLocks.computeIfAbsent(key, k -> new Object())) {
      if (enabled) disabled.remove(key);
      else {
        disabled.add(key);
        generations.merge(key, 1L, Long::sum);
        var init = initializing.remove(key);
        if (init != null) init.ready.complete(false);
        var resources = cleanups.remove(key);
        if (resources != null)
          for (var cleanup : resources)
            try {
              cleanup.run();
            } catch (Throwable error) {
              org.slf4j.LoggerFactory.getLogger(HandlerRegistry.class)
                  .warn("Plugin resource cleanup failed", error);
            }
      }
    }
  }

  public boolean enabled(String pluginId, BotId bot) {
    return !disabled.contains(Keys.join(pluginId, bot.key()));
  }

  public List<Handler> matching(Event event) {
    return handlers.stream()
        .filter(h -> enabled(h.pluginId(), event.bot()))
        .filter(
            h ->
                Arrays.stream(h.method().getParameterTypes())
                    .filter(Event.class::isAssignableFrom)
                    .allMatch(t -> t.isInstance(event)))
        .filter(h -> h.matches(event))
        .sorted(
            Comparator.comparingInt(Handler::priority)
                .thenComparing(Handler::pluginId)
                .thenComparing(Handler::id))
        .toList();
  }

  public Optional<Handler> find(String id, BotId bot) {
    return handlers.stream()
        .filter(h -> h.id().equals(id) && enabled(h.pluginId(), bot))
        .findFirst();
  }
}
