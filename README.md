# AstraQQBot

AstraQQBot 是面向 Java 21+ 的 QQ 官方机器人框架。插件作者编写普通、顺序、可阻塞的同步代码；框架负责把每次回调投递到 Java 21 虚拟线程，并统一处理并发限制、排队、60 秒默认超时、异常、取消与关闭。

## 要求

- JDK 21 或更高版本（编译目标为 Java 21，不使用 preview 特性）
- Maven 3.9+

## 快速开始

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_APP_SECRET"))
        .build();

try (QQBot bot = QQBot.create(config)) {
    QQBot.Plugin plugin = bot.plugin("hello");
    plugin.onMessage(message -> {
        User user = userRepository.find(message.userOpenId());
        MessageResponse response = message.replyText("Hello " + user.name());
    });
    bot.startWebSocket().join();
}
```

`onEvent`、`onMessage`、`onInteraction`、`onRelationship`、`onMessageStatus`、`onResource`、`onCommand` 和 `onError` 都接收 `EventHandler<T>`。其 `handle` 方法返回 `void` 且声明 `throws Exception`，所以插件可直接抛出受检异常。框架会记录并送入错误通道。

## 执行模型

- Gateway/Webhook 线程只解析、匹配和投递，`dispatch` 不等待业务回调。
- 每个插件拥有独立的 `newThreadPerTaskExecutor`，线程由 `Thread.ofVirtual()` 创建并命名为 `astraqqbot-plugin-{shardId}-{pluginName}-*`。
- 默认每插件最多并发 256 个任务、排队 512 个任务。接收线程不会等待许可；队列满立即以 `RejectedExecutionException` 拒绝，也绝不回退到接收线程执行。
- 每次调用都有 `QUEUED/RUNNING/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED` 原子状态以及事件和时间元数据。
- 默认超时 60 秒，只计算进入 `RUNNING` 后的回调时间，不包括排队。
- 同名插件句柄共享 runtime；任一句柄关闭都会关闭整个同名插件。关闭后可用同名创建全新 runtime，旧监听器不会保留。

```java
BotConfig config = BotConfig.builder()
        .appId("...")
        .clientSecret("...")
        .pluginTaskTimeout(Duration.ofSeconds(60))
        .maxConcurrentTasks(256)
        .maxPendingTasks(512)
        .pluginShutdownTimeout(Duration.ofSeconds(30))
        .build();

QQBot.Plugin slower = bot.plugin("reports", PluginExecutionOptions.builder()
        .taskTimeout(Duration.ofMinutes(3))
        .maxConcurrentTasks(32)
        .maxPendingTasks(100)
        .build());

QQBot.Plugin noTimeout = bot.plugin("stream", PluginExecutionOptions.builder()
        .disableTaskTimeout()
        .build());
```

`Duration.ZERO` 不是禁用标记，会被拒绝；禁用必须显式调用 `disableTaskTimeout()`。

## 同步消息与 OpenAPI

普通插件 API 同步返回结果，并在失败时抛出异常：

```java
MessageResponse sent = bot.sendPrivateMessage(userOpenId, "Hello");
MessageResponse reply = message.replyText("收到");
JsonNode member = bot.api().getGroupMember(groupId, memberId);
```

框架生命周期方法 `startWebSocket()` 仍返回 `CompletableFuture<Void>`，它在 READY 时完成。插件若主动调用第三方 fire-and-forget 异步 API 后立即返回，框架无法自动发现该任务；应优先使用同步 API，或在回调内显式等待其结果。

## 关闭与取消

```java
plugin.close();
boolean closing = plugin.isClosing();
boolean closed = plugin.isClosed();
```

插件关闭会先移除监听器并拒绝新任务，取消排队任务，然后等待运行任务；超过 `pluginShutdownTimeout` 后中断虚拟线程。`QQBot.close()` 先停止 Gateway/Webhook，再按相同规则关闭全部插件，最后关闭超时调度器和 HTTP 资源。两种 `close()` 都幂等。

超时和关闭只能通过 `Thread.interrupt()` 进行协作式取消。框架不使用也禁止 `Thread.stop()`，因此无法安全强杀忽略中断的无限循环或本地阻塞代码。虚拟线程也不是无限资源；插件共享可变状态仍必须线程安全。

更多文档见 [docs/getting-started.md](docs/getting-started.md)、[docs/architecture.md](docs/architecture.md)、[docs/configuration.md](docs/configuration.md) 和 [docs/plugin-quick-start.md](docs/plugin-quick-start.md)。
