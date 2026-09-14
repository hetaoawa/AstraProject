# WebSocket 使用指南

WebSocket 适合常驻机器人进程。框架会处理 Gateway 鉴权、心跳、会话恢复和断线重连。

## 启动

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .intents(Intents.GROUP_AND_C2C_EVENT)
        .build();

QQBot bot = QQBot.create(config);
bot.onError(Throwable::printStackTrace);
bot.startWebSocket().join();
```

`startWebSocket()` 返回的 Future 在首次 READY 时完成。应用应在此后保持运行，并在停止时调用 `bot.close()`。

## 事件订阅

`intents` 必须覆盖需要接收的事件。常用标记包括：

- `GROUP_AND_C2C_EVENT`：群聊和 C2C；
- `GUILD_MESSAGES`、`PUBLIC_GUILD_MESSAGES`：频道消息；
- `DIRECT_MESSAGE`：频道私信；
- `INTERACTION`：互动事件；
- `GUILD_MEMBERS`：频道成员事件；
- `FORUMS_EVENT`：论坛事件；
- `AUDIO_ACTION`：音频事件。

可以使用按位或组合多个标记。机器人还需要在 QQ 开放平台拥有对应权限。

## 重连配置

```java
BotConfig config = base.toBuilder()
        .reconnectInitialDelay(Duration.ofSeconds(2))
        .reconnectMaxDelay(Duration.ofSeconds(30))
        .build();
```

网络中断后会自动尝试恢复会话。进程重启后将建立新会话。业务处理仍需使用消息 ID 或事件 ID 做幂等，避免重连期间的重复事件造成重复写入。

## 多分片

```java
try (QQBotCluster cluster = QQBotCluster.create(config, shardCount)) {
    cluster.onMessage(message -> handle(message));
    cluster.onError(Throwable::printStackTrace);
    cluster.startWebSocket().join();
    new CountDownLatch(1).await();
}
```

`QQBotCluster` 会为每个分片创建一个 Bot。处理器可能在多个分片并发运行，共享状态和幂等存储需要支持并发访问。

## 使用须知

- 一个 `QQBot` 对应一个 Gateway 连接。
- 一个应用实例中只调用一次 `startWebSocket()`。
- 请为 ERROR/WARN 日志设置监控，及时发现持续重连和鉴权失败。
- 业务回调超时、拒绝和异常通过 `onError` 处理。
- 长期运行前应完成真实机器人压测、断网恢复和停机测试。
