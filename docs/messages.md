# 消息与平台请求

本页介绍插件怎样读取消息内容、处理引用和合并记录，以及构造常用平台请求。历史查询见 [消息历史](history.md)，申请和成员变更见 [事件与报文归档](events.md)。

## 消息内容

`Message.content()` 返回 `MessageContent`。平台没有提供的字段保留为 `null` 或空集合。

| 字段 | 内容 |
| --- | --- |
| `author`、`mentions` | `PlatformMember`，包含身份、名称、头像、机器人标记、`isYou`、`scope`、openid、角色、昵称和加入时间 |
| `reference` | 引用索引、可选的原消息 ID、回复正文、引用元素和完整格式化正文 |
| `merged` | 合并元素、解析后的格式化记录和原始正文 |
| `elements`、`parallel` | 递归消息元素；`message_type=101` 的 `msg_elements` 和旧格式 `parallel_message.msg_nodes` 都可读取 |
| `attachments`、`ark`、`channelArk`、`embeds` | 附件、群消息 Ark、频道 Ark 和 Embed 字段 |
| `sceneContext` | `source`、完整 `ext`、消息与引用索引、`authToken` |
| `metadata` | 群和频道标识、消息时间、编辑时间、`mentionEveryone`、消息序号等 |

`Message.text()` 保留平台正文，命令匹配使用这份正文。`Message.displayText()` 将引用和合并记录整理为便于阅读的文本。

群全量消息、群 AT 消息和单聊消息共用这套模型。`ark.arkType()` 保留 `picture` 等平台取值，`ark.title()`、`preview()`、`jumpUrl()` 等方法读取常见卡片字段，其他字段仍可从 `ark.fields()` 获取。`sceneContext.extensions()` 按第一个等号拆分扩展项，保留索引和令牌末尾的等号。附件保留语音 WAV 地址、ASR 参考文本、文件大小及未知原始字段。

作者的 `username()` 为账号昵称，`memberNickname()` 对应群消息中的 `author.member_nickname`；频道成员的 `member.nick` 仍通过 `nick()` 读取。三者分别保留，未提供时为 `null`。群消息中的 `group_id`、`group_openid`、`union_group_openid` 分别位于 `metadata.groupId()`、`groupOpenid()`、`unionGroupOpenid()`。

外层事件 `id`、消息体 `d.id` 和 `message_scene.ext` 中的 `msg_idx` 用途不同：被动回复使用 `message().id()`，引用使用消息索引，事件 ID 由 `data().platformEventId()` 获取。对照报文的字段表见 [当前群消息字段](features.md#当前群消息字段)。

## 自动使用被动回复

`context.messaging().send(chat, body)` 在群聊和单聊中优先使用收到的有效消息 ID。框架在事件入队前记录 ID，不要求插件处理这条消息，也不要求开启消息历史或报文归档。没有可用 ID 时才发送主动消息，仍受平台主动额度限制。

选择范围限定为当前机器人、环境、聊天场景和目标，优先使用最早到期的 ID。群聊每个 ID 在原消息发出后 5 分钟内最多使用 5 次，单聊为 60 分钟、4 次。重复推送不会重置次数或延长有效期，多个插件共享计数，重启后继续使用数据库记录。

`context.messaging().reply(event, body)` 始终使用该事件的消息 ID，并扣减同一份次数。原消息过期或次数用尽时返回错误，不会改用其他消息。`message_reference` 仍按插件提供的内容发送；复用被动 ID 不会自动添加引用展示。显式声明 `is_wakeup=true` 的单聊发送继续走互动召回。

ID 和序号在入队前预留。排队期间过期会返回错误，发送失败、取消和结果不明时不返还次数，也不自动重发或转成主动发送。高级 `PlatformApi.raw`、`call` 保留原有行为，需要插件自行管理消息 ID、序号和时效。

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

自平台 2026-11-16 更新后，批量移除接口只接收 `member_openids`，单次 1～20 个，响应为 `remove_members_result`。使用 `new Moderation.RemoveMembers(members)` 或 `kick(group, members)`。旧的双参数构造方法已弃用：`false` 仍可使用且不发送该字段，`true` 会在请求前报错，不会静默忽略拉黑意图。

`kickAndBlacklist` 现在先移除成员，再调用独立黑名单接口。只有移除结果为 `success` 才执行第二步，两步分别受限速和权限检查，整个过程不自动重试。独立黑名单接口仍需平台白名单权限，且只允许拉黑已不在群中的用户。

组合方法的 `RemoveResult.response()` 保留移除响应，`blacklistResponse()` 保留第二步响应，`addToMemberBlacklistFailOpenids()` 为第二步返回的 `fail_openids`，调用者需要检查是否存在部分失败。移除成功后若第二步请求异常，会抛出 `Moderation.BlacklistAfterRemovalException`，其 `removal()` 可取得已完成的移除结果，原始异常由 `getCause()` 获取；不要因此重复移除。

批量移除错误 `40103012` 表示群主关闭了“移除群成员”权限，`40103011` 表示机器人管理端关闭了该能力；异常保留平台错误码。详情见 [批量移除文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_batch_remove_members.post.html)。

`MessagePayload` 提供 `text`、`markdown`、`media`、`card`、`channelText`、`embed`、`ark` 工厂方法，也提供引用、键盘、按钮、权限和弹窗类型。Markdown 模板参数、媒体 `fileInfo` 和被动回复字段均有对应类型。普通回复建议使用 `Messaging.reply`，由它管理消息 ID、回复时效和序号。

### 查询单聊用户资料

```java
context.api().userInfo(userOpenid).thenAccept(info -> {
    String username = info.username();
    String avatar = info.avatar();
    Boolean acceptsActiveMessages = info.allowProactiveMsg();
});
```

`userInfo` 调用新增的 `GET /v2/users/{user_openid}/info`，返回 `UserInfo`，包含 `unionOpenid()`、`username()`、`avatar()`、`allowProactiveMsg()` 和完整 `response()`。开关为 `false` 表示不接收主动推送，字段缺失时为 `null`；查询结果不会自动禁止被动回复，也不会覆盖现有消息配额。平台最终决定发送资格。

同一机器人环境的用户查询合计限制为 10 次/秒。目标必须与机器人有好友关系，否则返回 `40184001`。请求成功后，运行时按请求路径中的 `user_openid` 保存资料，包含 `false` 的主动推送开关；不会因响应没有 `id` 而丢失资料，也不会把单聊用户当作群成员。跨机器人关联仍需显式启用 union 并收到有效标识。普通资料查询仍只读缓存，群资料的 `find(identity, true)` 不用于查询单聊用户。

只需通用响应时，可调用操作 ID `v2_users_user_openid_info.get`，路径参数为 `user_openid`；`astra-qq` 提供 `QqEndpoints.V2_USERS_USER_OPENID_INFO_GET` 常量。接口与错误说明见 [官方用户资料文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_info.get.html)。

### 图文卡片、群通知与素材图片

按 [官方变更记录](https://bot.q.qq.com/wiki/develop/api-v2/changelog.html) 核对至 2026-11-16，支持群聊和单聊的 `msg_type=8` 图文卡片：

```java
var card = MessagePayload.card(MessagePayload.Card.tuwen(
    "活动通知", "周末活动安排", "https://example.com/activity", "https://example.com/image.png"));
context.messaging().send(chat, card);
```

标题为 1～50 个字符，描述为 1～200 个字符，页面和图片地址必须使用 HTTPS，长度最多 1024 个字符。详情见 [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html)。

文本和 Markdown 可使用 `MessagePayload.AT_EVERYONE`（`<qqbot-at-everyone />`）。机器人需要群管理员权限，平台负责权限校验；框架限制同一机器人在同一群每天最多尝试一次，主动和被动消息共享该次数。

素材管理提供四个操作：`QqEndpoints.V2_MEDIA_IMAGES_PREPARE_POST`、`V2_MEDIA_IMAGES_COMPLETE_POST`、`V2_MEDIA_IMAGES_GET`、`V2_MEDIA_IMAGES_IMAGE_ID_DELETE_DELETE`。申请和完成上传可分别传入 `MediaImagePayload.Prepare`、`Complete`，图片二进制通过 `context.api().uploadPart(presignedUrl, bytes, headers)` 上传，`Content-Type` 必须与申请时一致。完成上传后，将响应中的 `url`（`qqbot-media://...`）写入 Markdown，`image_id` 用于查询与删除。上传限制、有效期和分页见 [官方素材管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html)。

已有的 Markdown `forceVerifyImageResource`、群管理接口及分享链接接口已与更新记录核对。分享链接请求仍为可选的 `callback_data`（最多 32 个字符），响应链接位于 `data.url`。

`RequestPayload.toMap()` 将非空字段编码为平台要求的 snake_case，时间与操作枚举也采用平台格式。需要扩展字段时，可以通过 `call`、`raw` 传入 Map。`PlatformException` 保留平台错误码、`traceId` 和结果是否明确的信息。

完整操作目录见 [QQ 协议接口覆盖](protocol-coverage.md)，发送频率和消息额度见 [接口限速与配额](rate-limits.md)。
