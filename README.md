# AstraQQBot

AstraQQBot 是面向 Java 21+ 的 QQ 官方机器人开发框架，提供 Bot API v2 鉴权、WebSocket、Webhook、事件订阅、消息收发、命令监听和常用 OpenAPI。

## 环境要求

- JDK 21 或更高版本
- Maven 3.9+

在项目的 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>top.hetaoawa</groupId>
    <artifactId>astra-qqbot</artifactId>
    <version>0.2.0</version>
</dependency>
```

## 五分钟启动机器人

先在环境变量中保存机器人凭证：

```text
QQ_BOT_APP_ID=你的 AppID
QQ_BOT_CLIENT_SECRET=你的 AppSecret
```

创建 Bot、注册消息处理器并启动 WebSocket：

```java
import top.hetaoawa.qqbot.BotConfig;
import top.hetaoawa.qqbot.Intents;
import top.hetaoawa.qqbot.QQBot;

import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.builder()
                .appId(System.getenv("QQ_BOT_APP_ID"))
                .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
                .intents(Intents.GROUP_AND_C2C_EVENT)
                .build();

        QQBot bot = QQBot.create(config);
        bot.onError(Throwable::printStackTrace);
        bot.plugin("echo").onMessage(message ->
                message.replyText("收到：" + message.content()));

        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        bot.startWebSocket().join();
        new CountDownLatch(1).await();
    }
}
```

`startWebSocket().join()` 在机器人进入 READY 后返回。应用需要继续保持运行；在服务停止时调用 `bot.close()`。完整示例见 [EchoBot.java](examples/EchoBot.java)。

## 常用能力

插件回调使用 `EventHandler<T>`，可以直接执行数据库、文件和同步 HTTP 操作，也可以抛出受检异常：

```java
bot.plugin("orders").onCommand("status", 100, false, command -> {
    Order order = orderService.find(command.argument(0));
    command.replyText(order.status());
});
```

消息和 OpenAPI 方法会同步返回结果：

```java
MessageResponse sent = bot.sendPrivateMessage(userOpenId, "Hello");
MessageResponse reply = message.replyText("收到");
JsonNode member = bot.api().getGroupMember(groupOpenId, memberOpenId);
```

## 使用须知

- 同一插件的普通回调可能并发执行。共享集合、缓存和业务状态需要保证线程安全。
- 回调默认最多运行 60 秒。耗时任务可为插件单独调整超时。
- 每个插件默认允许 256 个并发回调和 512 个等待任务。容量用尽时会报告 `RejectedExecutionException`。
- 同名插件句柄共享监听器作用域和执行配置。重复创建时需要传入相同的 `PluginExecutionOptions`。
- `plugin.close()` 会关闭同名插件的整个作用域；`bot.close()` 会关闭所有连接和插件。
- 第三方异步任务需要在回调返回前等待完成，否则框架无法记录它的最终结果。
- 消息事件可能重复到达。涉及扣款、发货、写库等操作时，必须使用消息 ID 或事件 ID 做幂等控制。
- AppSecret 只能通过环境变量或密钥管理服务注入，禁止写入源码、日志和公开配置。

## 文档

- [入门教程](docs/getting-started.md)
- [配置项](docs/configuration.md)
- [插件开发](docs/plugin-quick-start.md)
- [运行规则与使用边界](docs/architecture.md)
- [事件与消息模型](docs/events.md)
- [命令监听器](docs/commands.md)
- [发送与回复消息](docs/messages.md)
- [扩展 OpenAPI](docs/open-api.md)
- [日志与错误处理](docs/logging.md)
- [WebSocket 使用指南](docs/websocket.md)
- [Webhook 部署指南](docs/webhook.md)
- [功能支持列表](docs/support-matrix.md)
- [故障排查](docs/troubleshooting.md)

## 许可证

个人学习、研究、实验、爱好和其他非商业用途适用 [PolyForm Noncommercial License 1.0.0](LICENSE)。商业或营利用途需要取得单独书面授权，详情见 [商业授权说明](COMMERCIAL-LICENSE.md)。
