# 日志与调试

AstraQQBot 内置按 Bot 实例配置的控制台日志，不要求应用额外引入日志框架。日志覆盖：

- Bot、Gateway 和 Webhook 启停；
- Access Token 刷新，但不会输出 Token 或 AppSecret；
- OpenAPI 请求方法、路径、状态码和耗时，但不会输出鉴权头；
- Gateway/Webhook 收到的事件类型、ID、序列号及 ACK；
- 事件被哪个命名监听器捕获；
- 同步或异步监听器完成、失败及耗时；
- Gateway 重连、签名拒绝、HTTP 错误和插件异常。

## 日志级别

| 级别 | 用途 |
| --- | --- |
| `TRACE` | 心跳、缓存 Token 使用情况；可选输出原始事件载荷 |
| `DEBUG` | 事件接收、路由、插件捕获/完成、HTTP 请求与响应耗时 |
| `INFO` | Bot、Gateway、Webhook 的主要生命周期；默认级别 |
| `WARN` | 重连、签名拒绝、非成功 HTTP 响应等可恢复问题 |
| `ERROR` | Gateway、Webhook、插件和错误监听器异常 |
| `OFF` | 关闭框架控制台日志 |

级别是阈值。例如 `INFO` 会输出 INFO、WARN 和 ERROR，不输出 DEBUG 和 TRACE。

## 启用 DEBUG

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
        .logLevel(BotLogLevel.DEBUG)
        .build();
```

日志示例：

```text
2026-08-14 16:30:00.123 [DEBUG] [EVENT] [HttpClient-1-Worker-0] received type=C2C_MESSAGE_CREATE id=event-1 op=0 sequence=15
2026-08-14 16:30:00.124 [DEBUG] [PLUGIN] [HttpClient-1-Worker-0] captured handler=hello kind=message eventType=C2C_MESSAGE_CREATE eventId=event-1
2026-08-14 16:30:00.201 [DEBUG] [PLUGIN] [ForkJoinPool.commonPool-worker-1] completed handler=hello kind=message eventType=C2C_MESSAGE_CREATE eventId=event-1 elapsedMs=77
```

## 为插件监听器命名

同步监听器：

```java
bot.onMessage("help-plugin", message -> {
    // 同步处理；回调返回时记录 completed。
});
```

指定事件类型：

```java
bot.onEvent("FRIEND_ADD", "welcome-plugin", event -> {
    // 处理好友添加事件。
});
```

旧的匿名注册方式仍然兼容，但日志名称会是 JVM 生成的 Lambda 类名，不利于排查问题。

## 跟踪异步插件完成状态

监听器内调用发送消息、数据库或网络异步 API 时，使用异步注册方法并返回 `CompletionStage`：

```java
bot.onMessageAsync("hello-plugin", message -> {
    if (!"/hello".equals(message.content())) {
        return CompletableFuture.completedFuture(null);
    }
    return message.replyText("hello");
});
```

框架会在返回的 Stage 完成时记录 `completed`；异常完成时记录 `failed` 和堆栈。普通 `onMessage` 只能表示同步回调已经返回，无法判断回调内部未返回的 Future 是否完成。

对应的原始事件异步入口为：

```java
bot.onEventAsync("audit-plugin", event -> auditService.save(event));
bot.onEventAsync("GROUP_JOIN_REQUEST", "approval-plugin", event -> approvalService.handle(event));
```

标准化事件也提供 `onInteractionAsync`、`onRelationshipAsync`、`onMessageStatusAsync` 和 `onResourceAsync`。

## 输出原始事件载荷

原始载荷可能包含用户消息、OpenID 和其他业务数据，因此必须同时满足两个条件才会输出：

```java
.logLevel(BotLogLevel.TRACE)
.logEventPayloads(true)
```

生产环境不建议启用。框架即使在 TRACE 下也不会主动输出 AppSecret、Access Token、Authorization 请求头或 Webhook 签名。

## 错误处理

日志不能替代业务错误处理。仍应注册：

```java
bot.onError(error -> monitoringService.report(error));
```

插件异步任务应把 Future 返回给 `onMessageAsync`/`onEventAsync`。如果插件自行启动任务又不返回 Future，框架无法记录任务的最终完成状态。

## 多分片日志

`QQBotCluster` 中每个 Bot 会记录自己的 `shardId/shardCount`。Gateway 生命周期日志可据此判断具体分片是否 READY、关闭或正在重连。
