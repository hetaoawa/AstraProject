# Getting started

AstraQQBot requires JDK 21+. Configure credentials, create a plugin, register synchronous callbacks, then start a transport.

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_APP_SECRET"))
        .build();

try (QQBot bot = QQBot.create(config)) {
    bot.plugin("messages").onMessage(message -> {
        MessageResponse response = message.replyText("收到：" + message.content());
    });
    bot.startWebSocket().join();
}
```

Every plugin callback is submitted to a framework-managed Java 21 virtual thread. Registration and dispatch are non-blocking; callback completion defines task completion. Checked exceptions may be thrown directly and are reported through logging and `onError`.

`startWebSocket()` is a lifecycle API and still returns `CompletableFuture<Void>`; it completes at READY, not when the bot disconnects. Use your application lifecycle to keep the process alive. For Webhook startup, use `startWebhook()`.

Common message and `QQOpenApi` calls are synchronous, so their return or exception belongs to the managed plugin task. See configuration and architecture for timeout and shutdown rules.
