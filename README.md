# AstraQQBot

面向 Java 17+ 的 QQ 官方机器人轻量开发框架，封装 QQ Bot API v2 的鉴权、WebSocket Gateway、Webhook 回调以及 C2C、群聊、频道和频道私信消息收发。

> 当前版本为 `0.1.0`。能力清单已按 QQ 官方文档站点 `v1.30.0`（2026-09-12）核对；部分群成员管理接口仍处于官方内邀阶段。

## 已实现

- 自动获取和提前刷新 `access_token`。
- 获取 Gateway 地址并完成 Identify/Resume。
- 自动发送心跳、记录序列号、处理 ACK、断线指数退避重连。
- Webhook 回调地址验证、Ed25519 请求验签和 Callback ACK。
- 接收并标准化 C2C、群聊、文字子频道和频道私信消息事件。
- 发送 C2C/群聊/文字子频道/频道私信文本和 Markdown 消息。
- 支持频道消息与频道私信 Embed、URL 图片和 `multipart/form-data` 图片直传。
- 支持 C2C 流式消息、富媒体上传、Ark、Markdown 模板和消息键盘。
- 支持 C2C/群聊/频道消息撤回、互动响应、Reaction、置顶、公告、日程、论坛和音频控制。
- 支持机器人菜单/面板、群聊审批与禁言、群成员/黑名单管理，以及 Guild/Channel/成员/角色/权限管理。
- 标准化互动、好友/群关系、消息状态和资源变更事件。
- `QQBotCluster` 自动创建并管理多分片 Gateway 实例。
- 插件使用彼此隔离的有界固定线程池，框架统一管理过载和关闭生命周期。
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
                .onError("main-error-handler", Throwable::printStackTrace);

        bot.plugin("echo").onMessageAsync(event ->
                event.replyText("收到：" + event.content()));

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

- 群成员列表、详情、批量移除和黑名单接口在官方文档中标记为“内邀接入”，能否调用取决于机器人白名单和控制台权限。
- 频道私信仅对符合官方机器人类型和共同频道条件的会话开放；私信沙箱、主动消息额度和频道消息限频由平台执行。
- 框架不做业务级消息去重。QQ 可能重复推送同一消息，应用应结合消息 ID 和场景索引实现幂等。
- `QQBot.Plugin` 监听器默认在插件独享线程池中并发执行，不保证事件完成顺序；共享状态必须保证线程安全。
- 未使用真实机器人凭证执行端到端测试；协议行为以 QQ 官方平台实际响应为准。

## 线程语义变更

插件回调不再运行于 Gateway 或 Webhook 接收线程。`dispatch()` 返回只表示事件已经完成路由和任务投递，不表示插件处理完成。同步回调和 `on*Async` 回调都会先进入插件线程池；异步回调返回的 `CompletionStage` 会继续被框架跟踪。依赖同步副作用的调用方应等待自己的 Future、latch 或业务状态。

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
- [发送频道消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html)
- [频道私信](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html)
- [官方变更记录](https://bot.q.qq.com/wiki/develop/api-v2/changelog.html)
- 官方 SDK：[botgo](https://github.com/tencent-connect/botgo)、[botpy](https://github.com/tencent-connect/botpy)、[bot-node-sdk](https://github.com/tencent-connect/bot-node-sdk)
- JVM 参考：[zimoyin/qqbot-sdk](https://github.com/zimoyin/qqbot-sdk)、[Kloping/qqpd-bot-java](https://github.com/Kloping/qqpd-bot-java)

## 许可证

本项目采用双轨授权：

- 个人学习、研究、实验、爱好和其他非商业用途：遵循 [PolyForm Noncommercial License 1.0.0](LICENSE)。
- 商业或营利用途：必须事先取得著作权人的单独书面授权，见 [商业授权说明](COMMERCIAL-LICENSE.md)。

该许可证限制商业使用，因此本项目是“源码可用（source-available）”软件，不是 OSI 定义下的开源软件。
