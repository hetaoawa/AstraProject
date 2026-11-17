# 事件与报文归档

插件可通过事件对象读取已经解析的字段，也可以单独开启报文归档，保存完整的 Webhook 和 WebSocket 接收内容。消息字段见 [消息与平台请求](messages.md)，事件匹配注解见 [插件开发](plugins.md#事件注解)。

## 申请与成员变更

`RequestEvent.request()` 返回入群申请，包含申请人、邀请人、已知操作人、`joinRequestId`、风险提示、申请时间与来源、验证文本、审核问答和自动批准策略。

`NoticeEvent.memberChange()` 返回加入、离开或更新的目标成员，以及已知操作人、申请和验证字段。平台没有提供操作人时，该字段保持未知。入群申请只补充申请人资料，不登记为已加入成员。

## 事件类型

| 事件类别 | 类型化入口 |
| --- | --- |
| 六种聊天消息 | `MessageEvent.message().content()` |
| 三种频道撤回事件 | `MessageRecallEvent.originalMessage()`、`operator()` |
| 入群申请 | `RequestEvent.request()` |
| 群和频道成员变更 | `NoticeEvent.memberChange()` |
| 频道和子频道变更 | `NoticePayload.Guild`、`Channel` |
| 好友、单聊推送和群机器人状态 | `Friend`、`C2cPermission`、`GroupBotState` |
| 互动、授权和订阅 | `Interaction`（含 `resolved` 嵌套字段）、`Subscription` |
| 表态和消息审核 | `Reaction`、`Audit` |
| 音频、语音和直播成员 | `Audio`、`AudioMember` |
| 论坛和开放论坛 | `Forum`（主题、帖子、回复）、`ForumAudit` |
| 网关控制和 READY | `MetaEvent.details()`，含完整 payload |
| 未知事件 | `RawEvent.data().raw()`；归档另存完整 envelope |

通知类别在 `NoticeEvent.details()` 中有对应类型，事件经过队列保存和恢复后仍保留这些类型。字段依据 [官方事件订阅表](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/event-emit.html) 及对应模型文档整理。开放论坛正文保留平台提供的字符串。

## 当前消息用户与群统一标识

按平台 2026-11-16 更新，单聊发送者包含昵称、头像和 `user_openid`；群发送者包含头像、`member_openid`、`member_role`、`member_nickname`。群消息 `mentions` 还提供 `is_you`、`scope`（`all` 或 `single`）与群名片，均通过现有 `PlatformMember` 对应方法读取。各场景缺失字段保持为空，不根据其他标识推断。旧版 `union_user_account` 和消息元素 `author` 仍兼容读取，但当前文档不再承诺这些字段会下发。

群统一标识的读取入口：

| 来源 | 读取方式 |
| --- | --- |
| 群全量、群 AT 消息 | `event.message().content().metadata().unionGroupOpenid()` |
| 机器人加入、退出群聊 | 将 `event.details()` 作为 `NoticePayload.GroupBotState` 读取 `unionGroupOpenid()` |
| 群成员加入、退出 | `event.memberChange().unionGroupOpenid()` |
| 获取群基本信息 | 成功响应的 `union_group_openid`；运行时继续用于本地群资料关联 |

新增字段在队列保存和恢复后保留；旧记录仅在 `raw` 中有字段时也可恢复类型化值。字段在绑定第三方应用后才有效，跨机器人关联仍受 `union-enabled`、`union-scope` 和实际非空值约束，不能用它直接发送消息。详情见 [官方更新记录](https://bot.q.qq.com/wiki/develop/api-v2/changelog.html)。

## 开启报文归档

```yaml
astra:
  history:
    enabled: false
  event-archive:
    enabled: true
    retention-days: 30 # 0 表示长期保留
```

归档默认关闭，与消息历史的开关和保留期分别设置。开启任意一项都不会自动开启另一项。

```java
if (context.eventArchive().enabled()) {
    var page = context.eventArchive().search(new EventArchive.Query(
        context.bot(), Set.of("GROUP_JOIN_REQUEST", "INTERACTION_CREATE"),
        Instant.now().minusSeconds(3600), null, 0, 20));
}
```

记录包含 `receiptId`、机器人、接入方式、事件类型、opcode、序号、平台事件 ID、接收时间、原样完整报文字符串和类型化事件。类型集合为空时不限事件类型。关闭归档时，查询抛出 `EventArchiveDisabledException`。

## 保存与清理规则

归档在接收校验通过后、业务入队前写入。同一推送重复到达时，各次接收分别保存，业务去重仍按原规则执行。开启归档后，写入失败的业务事件不会得到接收确认。

WebSocket 保存完整文本包，包括 HELLO、心跳确认、重连、Invalid Session、READY 和 RESUMED。Webhook 在签名有效后保存，挑战验证包也会归档；无效签名的报文不保存。

有效报文解析失败时，归档保留原文和通用事件，该报文不算成功入队。未知事件同样保留原始字段。记录默认按接收时间保留 30 天，`retention-days: 0` 表示长期保留，清理周期见 [数据库升级与清理](architecture.md#数据库升级与清理)。
