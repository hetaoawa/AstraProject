# 入门教程

本教程使用 WebSocket 接收群聊和 C2C 消息，并回复收到的文本。

## 1. 准备环境

安装 JDK 21+ 和 Maven 3.9+，然后在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>top.hetaoawa</groupId>
    <artifactId>astra-qqbot</artifactId>
    <version>0.2.0</version>
</dependency>
```

把凭证放入环境变量：

```text
QQ_BOT_APP_ID=你的 AppID
QQ_BOT_CLIENT_SECRET=你的 AppSecret
```

请勿把 AppSecret 提交到 Git。

## 2. 创建并配置 Bot

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
        .intents(Intents.GROUP_AND_C2C_EVENT)
        .build();

QQBot bot = QQBot.create(config);
```

`intents` 决定机器人订阅的事件范围。使用频道、互动或论坛功能时，需要按需组合 [Intents](events.md) 中的位标记，并在 QQ 开放平台开通相应权限。

## 3. 注册插件

```java
bot.onError(Throwable::printStackTrace);

bot.plugin("echo").onMessage(message -> {
    System.out.println(message.eventType() + ": " + message.content());
    message.replyText("收到：" + message.content());
});
```

插件名称用于区分监听器和日志。普通回调可能并发执行，插件中的共享状态需要保证线程安全。

## 4. 启动和关闭

```java
Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
bot.startWebSocket().join();
new CountDownLatch(1).await();
```

`startWebSocket().join()` 等待首次 READY。随后应由主程序、Web 容器或服务管理器维持应用生命周期。应用退出时调用 `bot.close()`。

使用 Webhook 时调用：

```java
WebhookServer server = bot.startWebhook();
```

生产环境还需要 HTTPS 反向代理，配置方法见 [Webhook 部署指南](webhook.md)。同一批事件请选择一种接入方式，避免业务重复处理。

下一步可阅读 [插件开发](plugin-quick-start.md)、[发送与回复消息](messages.md) 和 [配置项](configuration.md)。
