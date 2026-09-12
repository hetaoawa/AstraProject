# 配置项

所有配置通过 `BotConfig.builder()` 构建，构建后的 `BotConfig` 不可变。

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `appId(String)` | 无 | QQ 开放平台 AppID，必填 |
| `clientSecret(String)` | 无 | QQ 开放平台 AppSecret，必填 |
| `apiBaseUri(String/URI)` | `https://api.bot.qq.com/` | OpenAPI 基础地址 |
| `accessTokenUri(String/URI)` | `https://bots.qq.com/app/getAppAccessToken` | Access Token 接口地址 |
| `intents(long)` | `Intents.PRIVATE_AND_GROUP` | Gateway 订阅位图 |
| `shard(int id, int count)` | `0, 1` | 当前实例的分片编号和总数 |
| `connectTimeout(Duration)` | 20 秒 | HTTP/WebSocket 建连超时 |
| `httpExecutorThreads(int)` | `4` | 每个 Bot 执行阻塞 HTTP 请求的专用线程数 |
| `pluginExecutorThreads(int)` | `2` | 每个插件默认工作线程数 |
| `pluginQueueCapacity(int)` | `256` | 每个插件默认等待队列容量 |
| `pluginShutdownTimeout(Duration)` | 5 秒 | 关闭时所有插件共享的最长等待时间 |
| `reconnectInitialDelay(Duration)` | 2 秒 | Gateway 首次重连等待时间 |
| `reconnectMaxDelay(Duration)` | 30 秒 | Gateway 指数退避上限 |
| `webhookAddress(String, int)` | `127.0.0.1:8080` | 内置 HTTP 服务器监听地址 |
| `webhookPath(String)` | `/qqbot/events` | Webhook 请求路径 |
| `userAgent(String)` | `AstraQQBot/0.1.0` | HTTP User-Agent |
| `logLevel(BotLogLevel)` | `INFO` | 框架控制台日志阈值 |
| `logEventPayloads(boolean)` | `false` | 仅在 TRACE 时允许输出原始事件载荷 |
| `commandPrefixes(String...)` | `/` | 命令前缀，可配置多个 |
| `commandSeparator(String)` | 空格 | 命令与参数的拆分分隔符 |

## Intents

`Intents` 提供官方事件位的常量。只处理 C2C 和群聊消息时推荐：

```java
.intents(Intents.GROUP_AND_C2C_EVENT)
```

多个 intents 使用按位或：

```java
.intents(Intents.GROUP_AND_C2C_EVENT | Intents.INTERACTION)
```

订阅没有权限的 intent 可能导致 Gateway 拒绝连接。只订阅业务实际需要的事件。

## 分片

```java
.shard(0, 4)
```

代表当前实例是 4 个分片中的第 0 个。需要自动创建和管理全部分片时使用：

```java
try (QQBotCluster cluster = QQBotCluster.create(config, 4)) {
    cluster.onEvent(System.out::println);
    cluster.startWebSocket().join();
}
```

`QQBotCluster` 会为每个分片复制配置并设置正确的 `(shardId, shardCount)`，统一启动和关闭连接。

## 插件执行器

全局默认值适合普通 I/O 型插件：

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .pluginExecutorThreads(2)
        .pluginQueueCapacity(256)
        .pluginShutdownTimeout(Duration.ofSeconds(5))
        .build();
```

重任务插件可以单独覆盖线程数和队列容量：

```java
PluginExecutionOptions options = PluginExecutionOptions.builder()
        .threads(4)
        .queueCapacity(512)
        .build();

QQBot.Plugin media = bot.plugin("media", options);
```

插件级配置优先于 `BotConfig` 默认值。同名插件作用域共享同一个执行器；用不同配置重复创建同名插件会抛出 `IllegalStateException`。`BotConfig.toBuilder()` 会保留全部插件执行配置。

## 沙箱和代理环境

需要使用非默认 API 地址时，可以覆盖两个 URI：

```java
.apiBaseUri("https://example.invalid/")
.accessTokenUri("https://example.invalid/app/getAppAccessToken")
```

这也便于在集成测试中指向本地 Mock Server。URI 必须由可信配置提供，避免把凭证发送到未知地址。

## 参数校验

构建配置时会检查：

- AppID、AppSecret、Webhook host/path、User-Agent 不能为空；
- `shardCount` 必须大于 0，`shardId` 必须在有效范围内；
- Webhook 端口必须位于 `1..65535`；
- HTTP 与插件执行器线程数、插件队列容量必须大于 0；
- 所有 Duration 必须为正数；
- 最大重连等待时间不得小于初始等待时间。
