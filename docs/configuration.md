# 配置项

使用 `BotConfig.builder()` 创建配置。`appId` 和 `clientSecret` 为必填项，推荐从环境变量或密钥管理服务读取。

## 连接与协议

| Builder 方法 | 默认值 | 用途 |
| --- | --- | --- |
| `apiBaseUri(...)` | `https://api.bot.qq.com/` | OpenAPI 基础地址 |
| `accessTokenUri(...)` | `https://bots.qq.com/app/getAppAccessToken` | Access Token 地址 |
| `intents(long)` | `Intents.PRIVATE_AND_GROUP` | Gateway 事件订阅范围 |
| `shard(id, count)` | `0, 1` | 当前分片编号和总分片数 |
| `connectTimeout(Duration)` | 20 秒 | HTTP 和 WebSocket 建连超时 |
| `reconnectInitialDelay(Duration)` | 2 秒 | 首次重连等待时间 |
| `reconnectMaxDelay(Duration)` | 30 秒 | 重连等待时间上限 |
| `userAgent(String)` | 框架默认标识 | HTTP User-Agent |

修改 `apiBaseUri` 或 `accessTokenUri` 会把凭证发送到新地址。请只使用可信服务地址。

## 插件执行

| Builder 方法 | 默认值 | 用途 |
| --- | ---: | --- |
| `maxConcurrentTasks(int)` | 256 | 每个插件允许同时运行的回调数 |
| `maxPendingTasks(int)` | 512 | 每个插件允许等待的回调数，可设为 0 |
| `pluginTaskTimeout(Duration)` | 60 秒 | 单次回调的运行超时，等待时间不计入 |
| `pluginShutdownTimeout(Duration)` | 30 秒 | 关闭插件时等待回调结束的最长时间 |
| `httpExecutorThreads(int)` | 4 | 同时处理网络请求和 Gateway 启动工作的线程数 |

普通机器人可沿用默认值。提高限制前，请先确认 QQ 平台限频、外部服务容量、数据库连接池大小和机器资源。

为单个插件配置独立限制：

```java
PluginExecutionOptions options = PluginExecutionOptions.builder()
        .maxConcurrentTasks(32)
        .maxPendingTasks(100)
        .taskTimeout(Duration.ofMinutes(3))
        .build();

QQBot.Plugin plugin = bot.plugin("reports", options);
```

`bot.plugin("name")` 使用 `BotConfig` 中的并发、队列和超时设置。调用 `bot.plugin("name", options)` 时，并发数和队列长度取自 `options`；未设置的 `taskTimeout` 继承 Bot 配置。`PluginExecutionOptions.builder()` 自带 256/512 的默认值。

需要关闭某个插件的任务超时时，调用：

```java
PluginExecutionOptions.builder()
        .disableTaskTimeout()
        .build();
```

超时必须使用正数。`null`、零和负数都会被拒绝。同名插件需要使用完全相同的执行配置。

## Webhook、日志与命令

| Builder 方法 | 默认值 | 用途 |
| --- | --- | --- |
| `webhookAddress(host, port)` | `127.0.0.1, 8080` | Webhook 监听地址 |
| `webhookPath(String)` | `/qqbot/events` | Webhook 回调路径 |
| `logLevel(BotLogLevel)` | `INFO` | 控制台日志级别 |
| `logEventPayloads(boolean)` | `false` | 在 TRACE 级别输出事件数据 |
| `commandPrefixes(...)` | `List.of("/")` | 一个或多个命令前缀 |
| `commandSeparator(String)` | 单个空格 | 命令参数分隔符 |

事件数据可能包含用户内容。生产环境开启 `logEventPayloads(true)` 前，应先确认数据安全和隐私要求。
