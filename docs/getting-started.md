# 入门与运行方式

## 环境要求

- JDK 17 或更高版本
- Maven 3.8 或更高版本
- 已在 QQ 开放平台创建机器人并取得 AppID、AppSecret
- 使用 Webhook 时，需要一个可被 QQ 平台访问的 HTTPS 地址

## 本地安装

```bash
git clone https://github.com/hetaoawa/AstraProject.git
cd AstraProject
mvn clean install
```

项目会安装为：

```text
top.hetaoawa:AstraQQBot:0.1.0-SNAPSHOT
```

## 凭证管理

推荐通过环境变量或密钥管理服务注入凭证：

```bash
export QQ_BOT_APP_ID="你的 AppID"
export QQ_BOT_CLIENT_SECRET="你的 AppSecret"
```

不要将凭证写入 Java 源码、配置示例、日志或 Git 历史。AppSecret 同时用于获取访问令牌和 Webhook Ed25519 签名处理。

## 选择接入模式

### WebSocket

适合本地开发或希望由客户端主动维持长连接的场景。框架会自动获取 Gateway、登录、维持心跳和重连。

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
        .build();

try (QQBot bot = QQBot.create(config)) {
    bot.onMessage(message -> System.out.println(message.content()));
    bot.startWebSocket().join();
    Thread.currentThread().join();
}
```

`startWebSocket()` 返回的 `CompletableFuture<Void>` 在收到 `READY` 后完成。它不是“运行到断开”的 Future，因此主进程需要由应用自己的生命周期管理器保持运行。

### Webhook

适合已有公网服务或由平台主动推送事件的场景：

```java
BotConfig config = BotConfig.builder()
        .appId(System.getenv("QQ_BOT_APP_ID"))
        .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
        .webhookAddress("127.0.0.1", 8080)
        .webhookPath("/qqbot/events")
        .build();

QQBot bot = QQBot.create(config)
        .onMessage(message -> message.replyText("收到"));

bot.startWebhook();
```

内置服务器只监听 HTTP。生产环境必须使用 Nginx、Caddy、云负载均衡或其他网关终止 TLS，再反向代理到本地监听地址。详见 [Webhook 部署与安全](webhook.md)。

## 优雅关闭

`QQBot` 实现了 `AutoCloseable`。关闭时会停止 Gateway 心跳和重连任务、关闭 WebSocket，并停止内置 Webhook 服务器。

长期运行的应用可注册关闭钩子：

```java
Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
```

## 下一步

- 调整 intents 和网络参数：[配置项](configuration.md)
- 订阅事件：[事件与消息模型](events.md)
- 主动发送或回复消息：[发送与回复消息](messages.md)
- 处理常见错误：[故障排查](troubleshooting.md)
