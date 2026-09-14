# 日志与错误处理

通过 `BotConfig` 设置日志：

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .logLevel(BotLogLevel.INFO)
        .logEventPayloads(false)
        .build();
```

生产环境建议使用 `INFO` 或 `WARN`。排查事件字段时，可临时启用 `TRACE` 和 `logEventPayloads(true)`。事件数据可能包含用户内容，启用前需要确认隐私、脱敏和日志保留策略。

注册统一错误处理器：

```java
bot.onError(error -> centralReporter.report(error));
```

也可以通过插件注册：

```java
bot.plugin("orders").onError(error -> orderReporter.report(error));
```

所有错误监听器都会收到 Bot 报告的错误。需要区分插件来源时，请结合异常信息和日志中的插件、处理器、事件类型及事件 ID。

常见错误类型：

| 类型 | 处理建议 |
| --- | --- |
| `BotApiException` | 检查 HTTP 状态、平台错误码和 trace ID |
| `TimeoutException` | 缩短回调或调整插件超时 |
| `RejectedExecutionException` | 检查流量、并发数和等待队列 |
| `CancellationException` | 检查插件或 Bot 是否正在关闭 |

请勿把 AppSecret、Access Token、Authorization 头、Webhook 签名或未脱敏的敏感消息写入日志。
