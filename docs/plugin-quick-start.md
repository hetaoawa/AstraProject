# Plugin quick start

A plugin is a registration scope plus an isolated runtime.

```java
QQBot.Plugin plugin = bot.plugin("greeter");

plugin.onMessage(message -> {
    User user = repository.find(message.userOpenId());
    message.replyText("Hello " + user.name());
});

plugin.onCommand("health", 100, false, command -> {
    command.replyText("ok");
});

plugin.onError(error -> audit.record(error));
```

Callbacks use `EventHandler<T>`:

```java
@FunctionalInterface
public interface EventHandler<T> {
    void handle(T event) throws Exception;
}
```

Do not create a pool merely to wrap blocking database, file or HTTP calls. Blocking synchronous work is expected on the managed virtual thread. The callback is successful only when the method returns; thrown checked/runtime exceptions are failures.

Tune exceptional workloads only when necessary:

```java
QQBot.Plugin imports = bot.plugin("imports", PluginExecutionOptions.builder()
        .maxConcurrentTasks(16)
        .maxPendingTasks(64)
        .taskTimeout(Duration.ofMinutes(5))
        .build());
```

The same plugin name shares one runtime. Closing any matching handle closes that whole scope:

```java
plugin.close();
plugin.isClosing();
plugin.isClosed();
```

After close, creating the same name produces a fresh runtime without old listeners. Plugin-owned databases or other resources still need explicit cleanup by plugin code.

Shared mutable plugin state must be thread-safe because callbacks can overlap and completion order is not guaranteed. A third-party fire-and-forget task is invisible to the framework; use a synchronous API or explicitly await it before returning.
