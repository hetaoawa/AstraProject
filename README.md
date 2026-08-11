# AstraProject

一个面向 Java 17+ 的 QQ 官方机器人开发框架，基于 QQ 官方 Bot API v2 实现：

- WebSocket Gateway：自动获取 Gateway、Identify/Resume、心跳、Heartbeat ACK、断线重连。
- Webhook：接收回调、处理回调验证、返回 HTTP Callback ACK。
- OpenAPI：自动获取并缓存 `access_token`，支持单聊和群聊文本/Markdown 消息发送。
- 消息接口：提供原始事件监听、按事件名监听、标准化私聊/群聊消息事件和 `replyText`。
- 设计上保留原始 JSON，官方新增字段不会被 SDK 丢弃。

本轮暂不实现频道业务；默认 intents 为 `GROUP_AND_C2C_EVENT`。

## 快速开始

```java
import io.github.hetaoawa.qqbot.BotConfig;
import io.github.hetaoawa.qqbot.Intents;
import io.github.hetaoawa.qqbot.QQBot;

public class Main {
    public static void main(String[] args) {
        BotConfig config = BotConfig.builder()
                .appId(System.getenv("QQ_BOT_APP_ID"))
                .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
                .intents(Intents.GROUP_AND_C2C_EVENT)
                .build();

        QQBot bot = QQBot.create(config)
                .onMessage(event -> event.replyText("收到：" + event.content()))
                .onError(Throwable::printStackTrace);

        bot.startWebSocket().join();
    }
}
```

群聊全量消息需要平台权限，并显式订阅 `Intents.GROUP_AND_C2C_EVENT`；无权限时 Gateway 会拒绝连接。

## Webhook

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
        .webhookAddress("127.0.0.1", 8080)
        .webhookPath("/qqbot/events")
        .build();

QQBot bot = QQBot.create(config).onMessage(event -> {
    event.replyText("Webhook 收到消息");
});
bot.startWebhook();
```

`WebhookServer` 是 JDK HTTP listener，生产环境应在反向代理或网关处终止 HTTPS，并将 QQ 配置的回调地址转发到该路径。签名验证使用 Bot Secret 派生 Ed25519 私钥，处理官方 `op=13` 验证请求。

## 发送消息

```java
bot.sendPrivateMessage(userOpenId, "你好");
bot.sendGroupMessage(groupOpenId, "你好，群聊");
bot.sendPrivateMessage(userOpenId, MessagePayload.markdown("# 标题\n内容"));
```

发送接口返回 `CompletableFuture<MessageResponse>`。主动消息、被动回复有效期、消息频控和 intents 权限仍由 QQ 平台控制，SDK 不会绕过这些限制。

## 构建

```bash
mvn test
```

## 官方文档与参考实现

- [QQ 机器人官方文档](https://bot.q.qq.com/wiki/develop/api-v2/)
- [WebSocket 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
- [Webhook 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/webhook.html)
- [安全和授权 / Webhook Ed25519 验签](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html)
- [通用数据结构与 Intents](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/payload.html)
- [发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)
- [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)
- 官方文档列出的 [botgo](https://github.com/tencent-connect/botgo)、[botpy](https://github.com/tencent-connect/botpy) 和 [bot-node-sdk](https://github.com/tencent-connect/bot-node-sdk)
- JVM 生态参考：[zimoyin/qqbot-sdk](https://github.com/zimoyin/qqbot-sdk)、[Kloping/qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)

## 许可证

MIT License
