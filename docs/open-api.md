# 扩展 OpenAPI 与管理能力

`QQBot.api()` 返回 `QQOpenApi`，可用于 C2C/群聊扩展、频道资源、权限、内容管理、消息撤回和互动操作。频道与频道私信发送使用 `QQBot`，私信会话创建和撤回使用 `QQOpenApi`。管理请求体采用 Jackson `JsonNode`，便于按官方文档填写字段。

`QQOpenApi` 请求采用同步调用方式。成功时返回 `JsonNode`，预签名分片上传返回 `void`；失败时抛出 `BotApiException`。在插件回调中调用时，请求耗时计入该回调的运行超时。

## C2C 与群聊消息扩展

```java
QQOpenApi api = bot.api();

api.recallPrivateMessage(userOpenId, messageId);
api.recallGroupMessage(groupOpenId, messageId);

api.streamPrivateMessage(userOpenId,
        StreamMessagePayload.markdown("正在生成", 0, false)
                .replyTo(messageId, 1));

api.uploadGroupMedia(groupOpenId,
        RichMediaRequest.fromUrl(RichMediaRequest.IMAGE, imageUrl)
                .serverSendsMessage(false));
```

上传接口返回的 `file_info` 可直接用于消息：

```java
MessagePayload payload = MessagePayload.media(fileInfo)
        .messageSequence(1);
bot.sendGroupMessage(groupOpenId, payload);
```

大文件流程对应 `preparePrivateMultipartUpload` / `prepareGroupMultipartUpload`、`uploadPresignedMediaPart` 和 `finishPrivateMultipartUpload` / `finishGroupMultipartUpload`。

## 模板、Ark 与键盘

```java
MessagePayload template = MessagePayload.markdownTemplate(
        templateId,
        Map.of("name", List.of("AstraQQBot")))
        .keyboardTemplate(keyboardId);

MessagePayload ark = MessagePayload.ark(23, arkKeyValues);
```

也可使用 `keyboardContent(JsonNode)` 构造自定义键盘。模板、Ark 和键盘能否在目标场景发送，以机器人权限和平台审核结果为准。

## 机器人与群聊管理

`QQOpenApi` 提供：

- `getMenu`、`updateMenu`；
- `listPanels`、`createPanel`、`getPanel`、`updatePanel`、`deletePanel`、`setPanelTarget`；
- `getGroupInfo`、`getGroupBotState`、分页 `listGroupJoinRequests`、`approveGroupJoinRequest`；
- `listGroupMembers`、`getGroupMember`、`batchRemoveGroupMembers`；
- `listGroupMemberBlacklist`、`updateGroupMemberBlacklist`；
- `getGroupRestrictChatSetting`、`updateGroupRestrictChatSetting`；
- 入群审批策略的查询、创建、更新、删除、执行和白名单维护；
- `generateUrlLink`。

## Guild、Channel 与内容管理

频道消息与频道私信发送见 [消息文档](messages.md)。`createDirectMessage` 创建私信会话，`recallDirectMessage` 撤回私信；频道消息撤回、互动以及以下资源管理能力也可用：

- 当前机器人资料、Guild 列表和详情；
- Channel 查询、创建、修改和删除；
- Guild 成员查询、移除、禁言和全员禁言；
- 角色增删改查、角色成员维护；
- Guild API 权限申请、Channel 成员/角色权限查询和修改；
- Reaction 查询、添加和移除；
- 消息置顶、Guild/Channel 公告；
- 日程增删改查、论坛主题增删查；
- 音频控制、上麦/下麦和在线成员数；
- 互动事件响应。

请求示例：

```java
ObjectNode channel = new ObjectMapper().createObjectNode()
        .put("name", "通知")
        .put("type", 0);
api.createGuildChannel(guildId, channel);

ObjectNode mute = new ObjectMapper().createObjectNode()
        .put("mute_seconds", "60");
api.muteGuildMember(guildId, userId, mute);
```

## 通用请求入口

官方增加端点而框架尚未发布对应便捷方法时，可以临时使用：

```java
api.request("PATCH", "/official/path", requestBody);
```

通用入口会使用 Bot 鉴权，并把非 2xx 响应转换为 `BotApiException`。使用前请核对官方路径、HTTP 方法、请求体、权限和限频要求；稳定业务优先选择已有的明确命名方法。

官方参考：[服务端接口](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/)、[群聊管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/group/)、[频道管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/guild/)。
