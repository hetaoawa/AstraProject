# 框架支持功能列表

本文档按 QQ 官方 Bot API v2 文档目录核对 AstraQQBot 的实现进度，记录时间为 **2026-08-15**。这里的“官方已开放”指官方开发文档当前列出的服务端接口、事件和消息能力；“框架支持”只统计本项目已经提供的 Java API，不把用户通过 `JsonNode` 自行扩展请求体视为完整封装。

官方入口：[启动接入](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/getting-started.html)、[API v2 开发文档](https://bot.q.qq.com/wiki/develop/api-v2/)。

## 总览

| 官方能力域 | 框架状态 | 当前实现 |
| --- | --- | --- |
| AppID/AppSecret、Access Token | ✅ 已支持 | `BotConfig` 配置凭证，自动获取并缓存 Access Token |
| WebSocket Gateway | ✅ 已支持 | 获取 Gateway、Identify、Resume、心跳、序列号、断线指数退避重连 |
| Webhook 回调 | ✅ 已支持 | 地址验证、Ed25519 验签、事件 ACK；生产环境需自行在前置网关终止 TLS |
| 原始事件接收 | ✅ 已支持 | `QQEvent` 保留 `op`、`type`、`data`、`raw`，支持全局和按事件类型监听 |
| C2C/单聊消息 | ✅ 已支持 | 接收 `C2C_MESSAGE_CREATE`，发送文本/Markdown，支持被动回复 |
| 群聊消息 | ✅ 已支持 | 接收 `GROUP_AT_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE`，发送文本/Markdown，支持被动回复 |
| 频道消息发送、标准化接收与频道私信 | ❌ 当前版本未支持 | 不提供频道消息发送、标准化接收和频道私信端点；频道消息撤回、互动及资源管理不受此限制 |
| C2C/群聊富媒体、Ark、模板、键盘、流式消息 | ✅ 已支持 | 提供载荷构造器、媒体直传/分片上传流程和 C2C 流式消息 API；Embed 属于频道消息，仍排除 |
| 消息互动、撤回、Reaction、置顶、公告、日程 | ✅ 已支持 | `QQOpenApi` 提供明确命名的方法；实际权限由平台控制 |
| 机器人、群聊、Guild/Channel 管理 | ✅ 已支持 | 菜单、面板、群资料/审批/禁言、Guild/Channel、成员、角色和权限 API |
| 互动事件、好友/群关系事件、消息状态事件 | ✅ 已支持 | 提供 `QQInteractionEvent`、`QQRelationshipEvent`、`QQMessageStatusEvent` 和专用监听器 |
| Guild/Channel、论坛和音频资源事件 | ✅ 已支持 | 提供 `QQResourceEvent` 和 `onResource` 监听器 |
| 多分片运行与分片调度 | ✅ 已支持 | `QQBotCluster` 自动创建、启动和关闭全部分片实例 |

## 已支持能力

### 接入与鉴权

- `BotConfig` 支持 AppID、AppSecret、API 地址、Access Token 地址和 User-Agent。
- `AccessTokenManager` 自动申请、缓存并在过期后刷新 Access Token。
- `QQBot.startWebSocket()` 启动 Gateway；`QQBot.startWebhook()` 启动本地回调服务。
- `WebhookServer` 实现官方回调地址验证、Ed25519 签名校验和回调响应。

对应官方参考：[获取访问凭证](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)、[事件订阅与通知](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/)、[签名校验](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html)。

### 事件与消息

- 原始事件通过 `onEvent(Consumer<QQEvent>)` 或 `onEvent(type, listener)` 接收。
- 标准化消息通过 `onMessage(Consumer<QQMessageEvent>)` 接收。
- 当前标准化消息事件为：
  - `C2C_MESSAGE_CREATE`
  - `GROUP_AT_MESSAGE_CREATE`
  - `GROUP_MESSAGE_CREATE`
- `QQMessageEvent` 提供消息 ID、用户/群 OpenID、作者、文本、消息场景、附件元数据和原始 JSON。
- `sendPrivateMessage`、`sendGroupMessage` 支持文本和 Markdown；`replyTo`、`eventReplyTo` 支持消息或事件回复字段。
- 未建模的官方字段可通过 `MessagePayload.put`、`set` 和 `raw` 保留或传递。

对应官方参考：[消息收发概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)、[消息类型](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/format.html)、[发送消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html)、[消息事件](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/event.html)。

## 当前未支持的官方能力

当前版本保留以下未实现项：

- 频道消息发送与接收；
- 频道私信创建、发送、接收与撤回；
- 仅能用于频道消息的 Embed 载荷。

Guild/Channel 资源管理、频道消息撤回、频道公告/置顶/日程/论坛/音频、Reaction 等非消息投递能力已经通过 `QQOpenApi` 提供。管理接口使用明确命名的方法和官方 JSON 请求体，以兼容官方字段扩展；这不等同于支持频道消息发送或标准化接收。

官方目录参考：[频道服务端接口](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/)、[消息类型](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/type/overview.html)、[消息互动](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/interaction.html)、[群聊管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/group/)、[频道管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/guild/)。具体接口是否对某类机器人开放，仍以 QQ 开放平台控制台权限和官方接口返回为准。

## 判断口径与边界

1. 官方文档列出的能力不等于所有机器人默认拥有权限；订阅位图、机器人类型、场景权限和平台策略仍会影响实际可用性。
2. 框架当前的原始事件分发有利于提前接入新事件，但不会自动完成字段校验、事件去重、资源模型转换或专用 API 封装。
3. 框架仍只实现 C2C/群聊消息发送端点；管理 API 中出现 `channelId` 不代表已经支持频道消息或频道私信。
4. 该列表是实现进度清单，不是 QQ 官方能力的完整 API 参考；新增功能时应同步更新本文件和对应的 API 文档链接。
