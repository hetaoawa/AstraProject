# Configuration

```java
BotConfig config = BotConfig.builder()
        .appId("...")
        .clientSecret("...")
        .pluginTaskTimeout(Duration.ofSeconds(60))
        .maxConcurrentTasks(256)
        .maxPendingTasks(512)
        .pluginShutdownTimeout(Duration.ofSeconds(30))
        .build();
```

| Option | Default | Meaning |
| --- | ---: | --- |
| `pluginTaskTimeout(Duration)` | 60 s | Maximum RUNNING time for each callback |
| `maxConcurrentTasks(int)` | 256 | Active virtual-thread callbacks per plugin |
| `maxPendingTasks(int)` | 512 | Bounded pending callbacks per plugin |
| `pluginShutdownTimeout(Duration)` | 30 s | Maximum shutdown wait, unrelated to task timeout |
| `httpExecutorThreads(int)` | 4 | Bounded internal HTTP/Gateway workers |

Per-plugin overrides are immutable and affect all tasks created by that runtime:

```java
PluginExecutionOptions options = PluginExecutionOptions.builder()
        .maxConcurrentTasks(32)
        .maxPendingTasks(100)
        .taskTimeout(Duration.ofMinutes(3))
        .build();

QQBot.Plugin plugin = bot.plugin("reports", options);
```

Disable timeout explicitly:

```java
PluginExecutionOptions.builder().disableTaskTimeout().build();
```

An absent plugin override inherits the global timeout. `null`, zero and negative durations are invalid; `Duration.ZERO` never means disabled.
