# 故障排查

## Bot 在 READY 后立即退出

`startWebSocket().join()` 只等待首次 READY。请让主程序、Web 容器或服务管理器继续维持进程，并在应用停止时调用 `bot.close()`。

## 回调超时

默认运行超时为 60 秒。可以为单个插件调整：

```java
bot.plugin("reports", PluginExecutionOptions.builder()
        .taskTimeout(Duration.ofMinutes(3))
        .build());
```

超时会请求线程中断。阻塞调用需要响应 `InterruptedException`，循环需要检查中断标记。确实需要长期运行时，可调用 `disableTaskTimeout()`，同时自行提供停止机制和监控。

## RejectedExecutionException

插件的并发额度和等待队列已经用满。按以下顺序排查：

1. 查看外部 API、数据库和文件操作耗时；
2. 检查是否出现死循环、锁等待或忽略中断；
3. 限制上游请求速度；
4. 结合下游容量调整 `maxConcurrentTasks` 和 `maxPendingTasks`。

## 关闭耗时较长

确认回调能够响应线程中断，并为数据库连接、HTTP 请求和文件操作设置超时。`pluginShutdownTimeout` 控制关闭时的最长等待时间。插件自有连接池、调度任务和文件句柄需要在插件关闭流程中主动释放。

## 消息或 OpenAPI 调用变慢

检查 QQ 平台响应时间、限频提示和网络质量。大量并发请求还可能受到 `httpExecutorThreads` 限制。提高该值前，应同步检查连接数、内存和平台配额。

## OpenAPI 调用失败

捕获 `BotApiException` 并记录：

```java
try {
    bot.sendGroupMessage(groupOpenId, "Hello");
} catch (BotApiException error) {
    System.err.printf("status=%d, code=%d, traceId=%s%n",
            error.httpStatus(), error.errorCode(), error.traceId());
}
```

根据 HTTP 状态、错误码和 trace ID 检查权限、参数、限频和平台日志。未知错误可以继续抛出，交给 `onError` 统一处理。

## 异步任务缺少完成或错误记录

第三方异步任务需要在插件回调返回前等待：

```java
thirdPartyCall().toCompletableFuture().join();
```

也可以让该任务完全由应用自行管理，并提供独立的关闭、超时、重试和错误监控。
