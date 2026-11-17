# 功能使用与限制

本页作为项目能力的查询入口，按 2026-11-17 的源码整理。插件通过 `astra-api` 使用这些功能，无需依赖 Spring。平台权限、订阅范围和实际额度由机器人账号决定；本地构建通过不能代替实际平台验证。

## 功能一览

| 能力 | 使用入口与说明 | 条件和主要限制 |
| --- | --- | --- |
| 多机器人与事件接收 | 配置 `astra.bots`，使用 [事件注解](plugins.md#事件注解) | 同一进程可配置多个机器人；AppID、环境、场景分别隔离；每个机器人环境选择 Webhook 或 WebSocket |
| 群全量、群 AT、单聊及频道消息 | `MessageEvent.message().content()`，见 [消息字段](messages.md#消息内容) | 群全量消息需在开放平台开启接收所有消息并正确订阅；只读实际收到的字段，缺失值为 `null` 或空集合 |
| 指定消息回复 | `context.reply(event, text)`、`messaging().reply(event, body)` | 始终使用指定消息 ID；群聊 5 分钟、5 次，单聊 60 分钟、4 次；过期或耗尽返回错误 |
| 自动复用被动 ID | `messaging().send(chat, body)`，见 [自动使用被动回复](messages.md#自动使用被动回复) | 仅群聊和单聊；优先使用当前聊天最早到期的有效 ID，全部不可用时才发送主动消息；不依赖历史或归档开关 |
| 主动消息、互动召回与限速 | `messaging().send`；单聊 Map 可指定 `is_wakeup=true` | 主动群聊每群、单聊每用户分别为 1000 条/日，另有每分钟、每秒限制；互动召回需存在有效互动，见 [完整配额规则](rate-limits.md) |
| 文本、Markdown、富媒体和图文卡片 | `MessagePayload.text`、`markdown`、`media`、`card`，见 [请求示例](messages.md#常用请求) | 富媒体先上传获取 `file_info`；图文卡片使用 `msg_type=8`，标题 1～50 字、描述 1～200 字、HTTPS 地址最长 1024 字符；频道另有 Embed、Ark 等格式 |
| 群内 @全体成员 | 在文本或 Markdown 中加入 `<qqbot-at-everyone />` | 需要群管理员权限；同一机器人、环境、群每天最多尝试 1 次，主动和被动共享次数 |
| 可复用的 Markdown 图片素材 | `api().call` 调用 `/v2/media-images` 相关操作，见 [素材图片说明](messages.md#图文卡片群通知与素材图片) | 先申请、PUT 上传、完成上传，再引用 `qqbot-media://`；单图不超过 10MB，支持 png/jpeg/gif/webp；平台数量与有效期仍需遵守 |
| 引用、并行和合并记录 | `Message.content()`、`Message.displayText()` | `msgid` 和引用索引不同；`message_reference` 需显式提供；未知格式保留原文，TMP 索引后续能否引用由平台决定 |
| 消息撤回 | `messaging().recall(chat, messageId)` | 只能在平台允许的时间与权限范围内撤回机器人消息；框架不会自动重试，见 [消息与平台请求](messages.md) |
| 群管理与其他平台接口 | `api().moderation()`、`api().call()`、`api().raw()` | 接口目录有 98 个操作 ID；移除成员单次最多 20 人，不再携带拉黑选项；`kickAndBlacklist` 分两步执行，需检查部分失败，见 [群管理用法](messages.md#常用请求) |
| 单聊用户资料查询 | `api().userInfo(userOpenid)`，见 [查询用户资料](messages.md#查询单聊用户资料) | 每机器人环境合计 10 次/秒；必须有好友关系，否则返回 `40184001`；返回昵称、头像、union 标识和主动推送开关，成功后更新本地资料 |
| 用户、群资料与成员关系 | `directory().find`、`search`、`members`、`relationships` | 返回已观察到的本地资料，不能当作完整成员列表；群名片按群保存，群角色不会覆盖全局资料 |
| 群资料刷新 | `directory().find(group, true)`，见 [群资料与刷新](plugins.md#群资料与刷新) | 强制刷新仅支持 `group_openid`，会等待平台结果；自动缓存 6 小时，失败后间隔 5 分钟再尝试；已解散群会被排除 |
| 跨机器人身份关联 | `directory().related(identity, offset, limit)` | 默认关闭；两端都需启用 union、配置相同且非空的 `union-scope`，并实际收到相同 union 值；群消息、资料接口、进退群和成员增减均支持 `union_group_openid`，见 [群统一标识](events.md#当前消息用户与群统一标识) |
| 消息历史、索引与上下文 | `history().findByIndex`、`context`、`search`，见 [消息历史](history.md) | 默认关闭；开启后记录新消息，无法补回关闭期间的正文；附件仅保存元信息，临时索引可能失效 |
| 通知、入群申请与撤回事件 | `RequestEvent`、`NoticeEvent`、`MetaEvent`，见 [事件说明](events.md) | 是否收到取决于平台订阅和权限；入群申请不会直接登记为已加入成员 |
| 完整报文归档 | `eventArchive().search()`，见 [开启归档](events.md#开启报文归档) | 默认关闭，与历史开关独立；默认保留 30 天；关闭时查询会抛出异常 |
| 等待用户下一条消息 | `sessions().await(...)`，见 [会话与任务](plugins.md#会话与任务) | 同一机器人、聊天、用户最多一个等待会话；仅存内存，超时、停用或重启后结束 |
| 定时任务 | `tasks()`、`@Scheduled` | 延时、固定周期、Cron；同一任务不重叠，默认时区为 Asia/Shanghai；重启后由插件重新注册，不补跑停机期间的任务 |
| 插件 HTTP 接口 | `httpRoutes().register()`，见 [HTTP 路由](http.md) | 与应用共享端口；请求体上限 2 MiB，等待响应上限 30 秒；插件停用后注销入口 |
| 插件服务与权限扩展 | `services()`、`PermissionService`，见 [权限服务](plugins.md#权限服务) | 按机器人提供服务；权限判断需要权限插件；插件作为可信代码运行，更新 JAR 后需要重启 |

## 消息 ID 的使用方式

```java
// 指定原消息回复，原消息过期或次数用尽时失败。
context.messaging().reply(event, MessagePayload.text("收到"));

// 先使用同一聊天中可用的消息 ID，没有时才发送主动消息。
context.messaging().send(event.chat(), MessagePayload.text("处理完成"));
```

两种方式共享每个 ID 的回复次数。记录、序号和到期时间保存在数据库，重复推送不会重置，多个插件也不会各获得一份额度。ID 和序号在入队前预留，失败、取消或结果不明时不返还；排队时过期会报错，不自动改成主动发送。

`PlatformApi.raw`、`call` 仍要求插件自行管理消息 ID、序号和时效，不自动选择 ID。频道和频道私信也不使用这个自动选择功能。每个接口的频率、主动额度、每日配额、冷却和自定义预算见 [接口限速与配额](rate-limits.md)。

## 当前群消息字段

以用户提供的 `GROUP_MESSAGE_CREATE` 报文结构为例：

| 平台字段 | 读取入口 | 含义和限制 |
| --- | --- | --- |
| 外层 `id` | `event.data().platformEventId()` | 事件 ID，与消息 ID 分开保存 |
| `d.id` | `event.message().id()` | 被动回复使用的消息 ID |
| `d.author.username` | `event.message().content().author().username()` | 用户账号昵称 |
| `d.author.member_nickname` | `event.message().content().author().memberNickname()` | 本群名片；不覆盖账号昵称；频道成员昵称仍使用 `nick()` |
| `d.group_id`、`d.group_openid` | `content.metadata().groupId()`、`groupOpenid()` | 原始群标识均保留；群聊发送目标使用 `group_openid` |
| `d.union_group_openid` | `content.metadata().unionGroupOpenid()` | 群的统一标识；参与跨机器人关联仍需显式启用 union |
| `message_scene.ext` 中的 `msg_idx` | `event.message().index()`、`content.sceneContext().messageIndex()` | 引用索引，不能代替被动回复的消息 ID |
| `message_scene.ext` 中的 `auth_token` | `content.sceneContext().authToken()` | 平台上下文令牌，按原值保留；不代替消息 ID |

表中的 `content` 指 `event.message().content()`。群 AT、群全量和单聊共用消息模型；没有群字段的单聊保持为空。原始消息字段通过 `raw()` 读取，完整外层报文（含 `op`、`s` 等）需要开启归档。

本地资料中的群名片保存在 `directory().relationships(chat, ...).items()` 对应关系的 `fields().get("member_nickname")`，不会写入用户全局资料。不同群的同一成员可以有不同名片。

## 文档维护

增加、删除能力或修改用法、默认值、额度和失败处理时，同步更新本页对应条目，并更新链接所指向的详细文档。接口操作变化需更新 [协议覆盖](protocol-coverage.md) 和 [限额清单](rate-limits.md)；验证结果、日期和未验证范围记录在 [验收记录](validation.md)。

本页不承诺平台权限已经开放。数据库升级与清理见 [架构与执行规则](architecture.md#数据库升级与清理)，配置默认值以 [配置示例](../config/application.example.yml) 和当前源码为准。
