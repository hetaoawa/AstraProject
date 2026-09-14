# 发送与回复消息

消息发送方法采用同步调用方式。成功时返回 `MessageResponse`，网络错误和平台错误会直接抛出。

## 发送消息

| 场景 | 方法 |
| --- | --- |
| C2C 单聊 | `sendPrivateMessage(userOpenId, ...)` |
| 群聊 | `sendGroupMessage(groupOpenId, ...)` |
| 文字子频道 | `sendChannelMessage(channelId, ...)` |
| 频道私信 | `sendDirectMessage(guildId, ...)` |

```java
MessageResponse privateMessage = bot.sendPrivateMessage(userOpenId, "Hello");

MessageResponse groupMessage = bot.sendGroupMessage(
        groupOpenId,
        MessagePayload.markdown("**Hello**").messageSequence(1));

MessageResponse channelMessage = bot.sendChannelMessage(
        channelId,
        MessagePayload.markdown("**Hello**"));
```

频道私信方法中的 `guildId` 必须使用私信会话 ID，可从 `createDirectMessage` 或 `DIRECT_MESSAGE_CREATE` 事件取得。

## 构造载荷

`MessagePayload` 支持：

- `text`：文本；
- `markdown`、`markdownTemplate`：Markdown 内容或模板；
- `media`：使用上传接口返回的 `file_info`；
- `ark`：Ark 模板；
- `embed`：频道 Embed；
- `channelImage`：频道 URL 图片；
- `raw`、`put`、`set`：使用官方字段扩展请求体；
- `keyboardTemplate`、`keyboardContent`：消息键盘。

载荷构造方法会修改当前 `MessagePayload` 并返回自身。每次发送都会读取一份副本。请为并发发送分别创建载荷，避免多个线程同时修改同一实例。

C2C 和群聊可以使用 `msg_type`、`msg_seq`、`force_verify_image_resource`。频道和频道私信请使用对应的频道消息字段；发送时会移除上述三个 C2C/群聊字段。

频道图片直传使用：

```java
bot.sendChannelImage(channelId, MessagePayload.text("图片"),
        "picture.png", "image/png", imageBytes);
```

频道私信图片使用 `sendDirectImage`，参数结构相同。

## 回复消息

最简单的回复方式：

```java
MessageResponse response = message.replyText("收到");
```

框架会根据消息中的群、频道、频道私信或 C2C 信息选择目标。事件必须带有消息 ID 和对应场景的目标 ID。

自定义回复载荷：

```java
MessagePayload payload = MessagePayload.text("第二条")
        .replyTo(message)
        .messageSequence(2);

bot.sendGroupMessage(message.groupOpenId(), payload);
```

`replyTo(message)` 写入消息 ID，并把消息序号初始化为 1。连续回复 C2C 或群消息时，请为后续消息设置递增的 `messageSequence`。`eventReplyTo(event)` 可用于需要 `event_id` 的请求。

发送频率、主动消息额度和载荷类型受 QQ 开放平台权限控制。成功后建议记录 `MessageResponse.id()`；捕获 `BotApiException` 时建议记录 trace ID，便于定位请求。
