# AstraQQBot

面向 Java 17+ 的 QQ 官方机器人轻量开发框架，封装 QQ Bot API v2 的鉴权、WebSocket Gateway、Webhook 回调以及单聊/群聊消息收发。

> 当前版本为 `0.1.0`。已支持 Guild/Channel 资源、权限和内容管理，但暂不包含频道消息发送、标准化接收和频道私信；其他管理接口仍可能调整。

## 已实现

- 自动获取和提前刷新 `access_token`。
- 获取 Gateway 地址并完成 Identify/Resume。
- 自动发送心跳、记录序列号、处理 ACK、断线指数退避重连。
- Webhook 回调地址验证、Ed25519 请求验签和 Callback ACK。
- 接收 `C2C_MESSAGE_CREATE`、`GROUP_AT_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE`。
- 发送单聊/群聊文本和 Markdown 消息。
- 支持 C2C 流式消息、富媒体上传、Ark、Markdown 模板和消息键盘。
- 支持 C2C/群聊/频道消息撤回、互动响应、Reaction、置顶、公告、日程、论坛和音频控制。
- 支持机器人菜单/面板、群聊审批与禁言，以及 Guild/Channel/成员/角色/权限管理。
- 标准化互动、好友/群关系、消息状态和资源变更事件。
- `QQBotCluster` 自动创建并管理多分片 Gateway 实例。
- 分级控制台日志记录传输生命周期、事件路由、插件捕获/完成和处理耗时。
- 命令监听器支持多前缀、参数拆分、优先级和事件传播控制。
- 原始事件、指定事件类型、标准化消息三种监听方式。
- 保留原始 JSON，便于兼容官方新增字段。

命令监听器详见 [`docs/commands.md`](docs/commands.md)。

## 快速开始

### 1. 引入项目

项目发布在 Maven Central。在应用的 `pom.xml` 中引入：

```xml
<dependency>
    <groupId>top.hetaoawa</groupId>
    <artifactId>astra-qqbot</artifactId>
    <version>0.1.0</version>
</dependency>
```

Maven 会自动下载主 JAR；IDEA 可通过 Maven 工具窗口的 **Download Sources and Documentation** 获取源码与 Javadoc。

### 2. 配置凭证

不要把 AppSecret 写入源码或提交到 Git。示例使用环境变量：

```bash
export QQ_BOT_APP_ID="你的 AppID"
export QQ_BOT_CLIENT_SECRET="你的 AppSecret"
```

### 3. 启动 WebSocket Bot

```java
import top.hetaoawa.qqbot.BotConfig;
import top.hetaoawa.qqbot.Intents;
import top.hetaoawa.qqbot.QQBot;

public class Main {
    public static void main(String[] args) {
        BotConfig config = BotConfig.builder()
                .appId(System.getenv("QQ_BOT_APP_ID"))
                .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
                .intents(Intents.GROUP_AND_C2C_EVENT)
                .build();

        QQBot bot = QQBot.create(config)
                .onMessage(event -> event.replyText("收到：" + event.content())
                        .exceptionally(error -> {
                            error.printStackTrace();
                            return null;
                        }))
                .onError("main-error-handler", Throwable::printStackTrace);

        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        bot.startWebSocket().join();
    }
}
```

完整示例见 [EchoBot.java](examples/EchoBot.java)。

## 文档

- [插件开发快速上手](docs/plugin-quick-start.md)
- [入门与运行方式](docs/getting-started.md)
- [配置项](docs/configuration.md)
- [事件与消息模型](docs/events.md)
- [发送与回复消息](docs/messages.md)
- [框架支持功能列表](docs/support-matrix.md)
- [扩展 OpenAPI 与管理能力](docs/open-api.md)
- [日志与调试](docs/logging.md)
- [WebSocket 生命周期](docs/websocket.md)
- [Webhook 部署与安全](docs/webhook.md)
- [架构与线程模型](docs/architecture.md)
- [故障排查](docs/troubleshooting.md)

## 当前边界

- 暂不支持频道消息发送、标准化接收和频道私信；频道消息撤回、Reaction 以及频道资源、权限和内容管理 API 已提供。
- Embed 属于频道消息载荷，因此随频道消息能力一并排除。
- 框架不做业务级消息去重。QQ 可能重复推送同一消息，应用应结合消息 ID 和场景索引实现幂等。
- 事件监听器在接收线程中同步执行，耗时任务应自行转交业务线程池。
- 未使用真实机器人凭证执行端到端测试；协议行为以 QQ 官方平台实际响应为准。

## 构建和测试

```bash
mvn clean test
```

项目要求 JDK 17。仓库内 GitHub Actions 会在每次 push 和 pull request 时运行测试。

## 官方资料与参考实现

- [QQ 机器人官方文档](https://bot.q.qq.com/wiki/develop/api-v2/)
- [WebSocket 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html)
- [Webhook 方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/webhook.html)
- [Webhook Ed25519 验签](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html)
- [通用数据结构与 Intents](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/payload.html)
- [发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html)
- [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)
- 官方 SDK：[botgo](https://github.com/tencent-connect/botgo)、[botpy](https://github.com/tencent-connect/botpy)、[bot-node-sdk](https://github.com/tencent-connect/bot-node-sdk)
- JVM 参考：[zimoyin/qqbot-sdk](https://github.com/zimoyin/qqbot-sdk)、[Kloping/qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)

## 许可证

本项目采用双轨授权：

- 个人学习、研究、实验、爱好和其他非商业用途：遵循 [PolyForm Noncommercial License 1.0.0](LICENSE)。
- 商业或营利用途：必须事先取得著作权人的单独书面授权，见 [商业授权说明](COMMERCIAL-LICENSE.md)。

该许可证限制商业使用，因此本项目是“源码可用（source-available）”软件，不是 OSI 定义下的开源软件。
