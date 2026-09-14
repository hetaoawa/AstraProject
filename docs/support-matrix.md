# 框架支持功能列表

本文档列出 AstraQQBot 已提供的 QQ Bot API v2 能力。官方能力清单核对于 **2026-09-12**，当时文档站点版本为 `v1.30.0`；项目 API 复核于 **2026-09-14**。实际可用范围取决于机器人类型、控制台权限、平台审核和接口限频。

官方入口：[启动接入](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/getting-started.html)、[API v2 开发文档](https://bot.q.qq.com/wiki/develop/api-v2/)。

## 总览

| 能力域 | 状态 | 可用方式 |
| --- | --- | --- |
| AppID/AppSecret、Access Token | ✅ 已支持 | `BotConfig` 配置凭证，自动获取并缓存 Access Token |
| WebSocket Gateway | ✅ 已支持 | `startWebSocket()`，支持心跳、会话恢复和断线重连 |
| Webhook 回调 | ✅ 已支持 | 地址验证、Ed25519 验签、事件 ACK；生产环境需自行在前置网关终止 TLS |
| 原始事件接收 | ✅ 已支持 | `onEvent(...)`、`QQEvent.data()`、`QQEvent.raw()` |
| C2C/单聊消息 | ✅ 已支持 | 接收 `C2C_MESSAGE_CREATE`，发送文本/Markdown，支持被动回复 |
| 群聊消息 | ✅ 已支持 | 接收 `GROUP_AT_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE`，发送文本/Markdown，支持被动回复 |
| 频道消息发送、标准化接收与频道私信 | ✅ 已支持 | 频道/私信发送、自动回复、会话创建、撤回，以及 `AT_MESSAGE_CREATE`、`MESSAGE_CREATE`、`DIRECT_MESSAGE_CREATE` 标准化接收 |
| 消息载荷与富媒体 | ✅ 已支持 | C2C/群聊媒体直传和分片上传、C2C 流式消息、Ark、Markdown 模板、键盘；频道支持 Embed、URL 图片和 multipart 图片直传 |
| 消息互动、撤回、Reaction、置顶、公告、日程 | ✅ 已支持 | `QQOpenApi` 提供明确命名的方法；实际权限由平台控制 |
| 机器人、群聊、Guild/Channel 管理 | ✅ 已支持 | 菜单、面板、群资料/审批/禁言/成员/黑名单、Guild/Channel、成员、角色和权限 API |
| 互动事件、好友/群关系事件、消息状态事件 | ✅ 已支持 | 提供 `QQInteractionEvent`、`QQRelationshipEvent`、`QQMessageStatusEvent` 和专用监听器 |
| Guild/Channel、论坛和音频资源事件 | ✅ 已支持 | 提供 `QQResourceEvent` 和 `onResource` 监听器 |
| 多分片运行 | ✅ 已支持 | 使用 `QQBotCluster` 创建、启动和关闭全部分片 |

## 已支持能力

### 接入与鉴权

- `BotConfig` 支持 AppID、AppSecret、API 地址、Access Token 地址和 User-Agent。
- Access Token 会自动申请、缓存和刷新。
- `QQBot.startWebSocket()` 启动 Gateway；`QQBot.startWebhook()` 启动本地回调服务。
- `WebhookServer` 支持地址验证、Ed25519 签名校验和事件 ACK。

对应官方参考：[获取访问凭证](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)、[事件订阅与通知](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/)、[签名校验](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html)。

### 事件与消息

- 原始事件通过 `onEvent(EventHandler<QQEvent>)` 或 `onEvent(type, listener)` 接收。
- 标准化消息通过 `onMessage(EventHandler<QQMessageEvent>)` 接收；处理器可以直接抛出异常。
- 监听器支持并发、排队、超时和关闭配置；不同业务可使用不同插件名称隔离容量。
- 当前标准化消息事件为：
  - `C2C_MESSAGE_CREATE`
  - `GROUP_AT_MESSAGE_CREATE`
  - `GROUP_MESSAGE_CREATE`
  - `AT_MESSAGE_CREATE`
  - `MESSAGE_CREATE`
  - `DIRECT_MESSAGE_CREATE`
- `QQMessageEvent` 提供消息 ID、用户/群 OpenID、Guild/Channel ID、频道消息顺序号、作者、成员、文本、消息场景、附件元数据和原始 JSON。
- `sendPrivateMessage`、`sendGroupMessage`、`sendChannelMessage`、`sendDirectMessage` 支持对应场景消息；`replyText` 根据事件场景自动选择端点。
- `MessagePayload.embed`、`channelImage` 与 multipart 图片发送覆盖频道专属载荷。
- 未建模的官方字段可通过 `MessagePayload.put`、`set` 和 `raw` 保留或传递。

对应官方参考：[消息收发概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html)、[消息类型](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/format.html)、[发送消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html)、[消息事件](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/event.html)。

## 2026-09 官方新增能力

官方 2026-09-03 变更记录新增群成员管理接口。框架现已提供 `listGroupMembers`、`getGroupMember`、`batchRemoveGroupMembers`、`listGroupMemberBlacklist` 和 `updateGroupMemberBlacklist`，并为 `listGroupJoinRequests` 增加 `cursor`/`limit` 分页参数。官方仍将成员列表、详情、批量移除和黑名单接口标记为内邀能力（错误码 `11253` 表示未获白名单权限）。

Guild/Channel 资源管理、频道消息和私信、频道公告/置顶/日程/论坛/音频、Reaction 等能力均已通过 `QQBot` 或 `QQOpenApi` 提供。管理接口使用明确命名的方法和官方 JSON 请求体，以兼容官方字段扩展。

官方目录参考：[频道服务端接口](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/)、[消息类型](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/type/overview.html)、[消息互动](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/interaction.html)、[群聊管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/group/)、[频道管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/guild/)。具体接口是否对某类机器人开放，仍以 QQ 开放平台控制台权限和官方接口返回为准。

## 判断口径与边界

1. 使用接口前，请在 QQ 开放平台确认机器人类型、订阅位图、场景权限和审核状态。
2. 原始事件需要由应用自行校验字段并实施事件去重。
3. 新增的请求字段可先通过 `JsonNode`、`MessagePayload.put`、`set` 或 `raw` 接入。
4. 新增 multipart 文件字段需要按照官方接口定义扩展。
5. 参数格式、权限和限频规则以 QQ 官方文档及接口响应为准。
