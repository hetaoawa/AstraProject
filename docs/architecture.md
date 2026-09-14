# 运行规则与使用边界

本页说明应用能够依赖的运行规则，以及编写插件时需要遵守的边界。

## 回调规则

- Gateway 和 Webhook 收到事件后，会尽快安排所有匹配的监听器。
- 普通监听器可以并发运行，开始和完成顺序均不固定。
- 命令监听器按优先级逐个运行，传播规则见 [命令监听器](commands.md)。
- 一个插件达到容量上限时，其他插件仍可继续处理事件。
- 监听器抛出的异常会进入 Bot 错误通道，其他已匹配的普通监听器仍会继续运行。

直接在 `QQBot` 上注册的监听器共用一组默认执行限制。通过 `bot.plugin(name)` 注册的监听器按插件名称分别使用各自的限制。需要隔离不同业务流量时，请为它们使用不同插件名称。

## 并发与排队

每个插件默认允许 256 个回调同时运行，并允许 512 个回调等待。两项容量都用尽后，新回调会以 `RejectedExecutionException` 进入错误通道。

配置建议：

- 数据库密集型插件：并发数应与数据库连接池容量匹配。
- 外部 API 密集型插件：结合对方限频和超时设置并发数。
- 顺序敏感业务：在业务层按用户、群或订单键串行化，或使用数据库事务和幂等键。
- 突发流量：保留有限队列并监控拒绝错误，避免无限积压。

## 超时与中断

回调默认拥有 60 秒运行时间，排队等待不占用这段时间。超时后会报告 `TimeoutException`，并请求回调线程中断。

插件代码需要配合中断：

```java
while (!Thread.currentThread().isInterrupted()) {
    processNextBatch();
}
```

对 `InterruptedException` 进行清理后应尽快返回；需要继续向上抛出时，可以恢复中断标记。忽略中断的代码可能继续运行，并与后续任务重叠。

## 关闭规则

`plugin.close()` 会停止接收该插件的新回调，取消等待中的回调，并等待正在处理的回调结束。等待超过 `pluginShutdownTimeout` 后会请求这些回调中断。

`bot.close()` 会关闭接入连接和全部插件。多个插件共用一次 Bot 关闭等待期限。应用应在 JVM Shutdown Hook、服务容器销毁回调或显式停止流程中调用它。

## 消息与 OpenAPI

消息发送和 `QQOpenApi` 方法采用同步调用方式。成功时直接返回结果，失败时抛出异常。插件回调可以按普通顺序代码编写：

```java
JsonNode member = bot.api().getGroupMember(groupId, memberId);
MessageResponse response = message.replyText(member.path("nickname").asText());
```

网络调用时间会计入回调超时。大量并发请求还会受到 `httpExecutorThreads` 和 QQ 平台限频影响。
