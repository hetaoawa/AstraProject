# 插件开发

插件用于集中注册一组监听器，并为这组回调设置独立的并发、排队和超时限制。

## 注册监听器

```java
QQBot.Plugin plugin = bot.plugin("greeter");

plugin.onMessage(message -> {
    User user = repository.find(message.userOpenId());
    message.replyText("Hello " + user.name());
});

plugin.onCommand("health", 100, false, command ->
        command.replyText("ok"));

plugin.onError(error -> audit.record(error));
```

回调统一使用 `EventHandler<T>`：

```java
@FunctionalInterface
public interface EventHandler<T> {
    void handle(T event) throws Exception;
}
```

回调可以直接执行同步数据库、文件和 HTTP 操作。方法返回代表本次处理完成；异常可以继续向外抛出，由错误监听器统一记录。

如果回调启动了第三方异步任务，请在返回前显式等待结果。提前返回会让该异步任务脱离插件的超时、错误和关闭管理。

## 设置资源限制

```java
QQBot.Plugin imports = bot.plugin("imports", PluginExecutionOptions.builder()
        .maxConcurrentTasks(16)
        .maxPendingTasks(64)
        .taskTimeout(Duration.ofMinutes(5))
        .build());
```

耗时任务应设置合理超时，并确保阻塞调用和循环能够响应线程中断。共享集合、缓存、连接和业务状态必须支持并发访问。

`plugin.onError` 会接收 Bot 报告的全部错误。需要区分来源时，可结合异常信息和日志中的插件名称处理。

## 插件名称与关闭

同一 Bot 中的同名插件句柄共享监听器作用域。再次获取同名插件时，需要沿用首次创建的 `PluginExecutionOptions`。

```java
plugin.close();
boolean closing = plugin.isClosing();
boolean closed = plugin.isClosed();
```

关闭任一同名句柄会关闭整个作用域。关闭完成后可以重新使用该名称，新插件从空监听器集合开始。数据库、文件和其他插件自有资源需要由插件代码主动释放。

## 开发红线

- 请勿假设普通监听器按注册顺序执行。
- 请勿在并发回调中使用未加保护的可变共享状态。
- 请勿忽略线程中断；超时和关闭依赖回调主动结束。
- 请勿启动无人管理的异步任务后立即返回。
- 请勿在监听器中记录 AppSecret、Access Token 或完整敏感消息。
