# 消息与平台请求

本页介绍插件怎样读取消息内容、处理引用和合并记录，以及构造常用平台请求。历史查询见 [消息历史](history.md)，申请和成员变更见 [事件与报文归档](events.md)。

## 消息内容

`Message.content()` 返回 `MessageContent`。平台没有提供的字段保留为 `null` 或空集合。

| 字段 | 内容 |
| --- | --- |
| `author`、`mentions` | `PlatformMember`，包含身份、名称、头像、机器人标记、`isYou`、`scope`、openid、角色、昵称和加入时间 |
| `reference` | 引用索引、可选的原消息 ID、回复正文、引用元素和完整格式化正文 |
| `merged` | 合并元素、解析后的格式化记录和原始正文 |
| `elements`、`parallel` | 递归消息元素和 `parallel_message.msg_nodes` |
| `attachments`、`ark`、`channelArk`、`embeds` | 附件、群消息 Ark、频道 Ark 和 Embed 字段 |
| `sceneContext` | `source`、完整 `ext`、消息与引用索引、`authToken` |
| `metadata` | 群和频道标识、消息时间、编辑时间、`mentionEveryone`、消息序号等 |

`Message.text()` 保留平台正文，命令匹配使用这份正文。`Message.displayText()` 将引用和合并记录整理为便于阅读的文本。

## 引用与合并记录

格式化解析支持以下平台标记：

- `=== 消息 N ===`。
- `[消息内容]`、`[发送者]`、`[消息类型]`、`[关联消息]`。
- 按四个空格增加缩进的 `--- 第N条 ---`。

这些格式可用于引用合并记录和多层引用。遇到未知格式时，`complete` 为 `false`，展示完整原文。框架不会根据显示名称推断用户身份。

单条引用、TMP 引用合并记录和直接合并记录的样本已用于此前验证。原始换行与格式标记保存在 `original`、`raw` 等字段中，检查范围见 [验收记录](validation.md#消息事件与扩展验证)。

## 消息索引

`MessageIndex.kind()` 用于区分索引：

| 类型 | 含义 |
| --- | --- |
| `STABLE` | REFIDX 稳定索引 |
| `TEMPORARY` | TMP 临时索引 |
| `UNKNOWN` | 尚未识别的索引类型 |

`observedIn` 记录索引出现在哪次事件中。TMP 只在本次观察中提供临时引用能力，后续引用是否可用由平台决定。正文相同的消息不会因此合并索引，引用请求也不会自动重试。

只有平台明确给出相同消息 ID 时，框架才将索引保存为同一历史记录的别名。本地查到记录后，临时索引仍可能已经失效。引用快照没有原消息 ID 时，框架保留已知内容，不补造原消息身份。

## 常用请求

```java
var mute = new Moderation.GroupMute(List.of(new Moderation.MemberMute(
    Moderation.MuteOperation.ADD, memberOpenid,
    OffsetDateTime.now().plusMinutes(10))));
context.api().moderation().muteGroup(groupOpenid, mute);
context.api().moderation().kick(groupOpenid, List.of(memberOpenid));
context.api().moderation().kickAndBlacklist(groupOpenid, List.of(memberOpenid));
context.messaging().reply(event, MessagePayload.markdown(
    MessagePayload.Markdown.content("**收到**"), null));
```

`Moderation` 还提供黑名单、入群审批、频道成员禁言和踢人操作。请求会检查成员数量、非空标识、操作类型和时间格式。频道踢人时，删除历史消息的窗口只接受 `-1`、`0`、`3`、`7`、`15`、`30`。

`kickAndBlacklist` 使用批量移除接口的 `addToMemberBlacklist` 标志，在一个请求中踢出并加入黑名单。结果保留失败成员和完整 `ApiResponse`，调用者需要检查是否存在部分失败。

`MessagePayload` 提供 `text`、`markdown`、`media`、`channelText`、`embed`、`ark` 工厂方法，也提供引用、键盘、按钮、权限和弹窗类型。Markdown 模板参数、媒体 `fileInfo` 和被动回复字段均有对应类型。普通回复建议使用 `Messaging.reply`，由它管理消息 ID、回复时效和序号。

`RequestPayload.toMap()` 将非空字段编码为平台要求的 snake_case，时间与操作枚举也采用平台格式。需要扩展字段时，可以通过 `call`、`raw` 传入 Map。`PlatformException` 保留平台错误码、`traceId` 和结果是否明确的信息。

完整操作目录见 [QQ 协议接口覆盖](protocol-coverage.md)，发送频率和消息额度见 [接口限速与配额](rate-limits.md)。
