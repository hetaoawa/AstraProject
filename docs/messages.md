# 发送与回复消息

所有发送方法返回 `CompletableFuture<MessageResponse>`。网络请求和平台业务错误会以异常完成，不应忽略 Future。

## 主动发送文本

```java
bot.sendPrivateMessage(userOpenId, "你好");
bot.sendGroupMessage(groupOpenId, "群消息");
bot.sendChannelMessage(channelId, "频道消息");
bot.sendDirectMessage(directGuildId, "频道私信");
```

## 发送 Markdown

```java
MessagePayload payload = MessagePayload.markdown("# 标题\n正文");
bot.sendPrivateMessage(userOpenId, payload);
```

是否能发送 Markdown 取决于机器人权限和平台策略。

## 频道专属消息

频道与频道私信支持 Embed：

```java
ObjectNode embed = new ObjectMapper().createObjectNode()
        .put("title", "构建完成")
        .put("prompt", "状态通知");
bot.sendChannelMessage(channelId, MessagePayload.embed(embed));
```

URL 图片可通过平台转存：

```java
bot.sendChannelMessage(channelId,
        MessagePayload.channelImage("https://example.com/image.png"));
```

本地图片字节使用官方 `multipart/form-data` 的 `file_image` 字段：

```java
bot.sendChannelImage(channelId, MessagePayload.text("图片说明"),
        "image.png", "image/png", imageBytes);
bot.sendDirectImage(directGuildId, MessagePayload.empty(),
        "image.png", "image/png", imageBytes);
```

同一个 `MessagePayload` 用于频道端点时，框架会移除只属于 C2C/群聊协议的 `msg_type`、`msg_seq` 和 `force_verify_image_resource` 字段。

## 被动回复

最简单的方式：

```java
bot.plugin("reply").onMessageAsync(message -> message.replyText("收到"));
```

或显式构造请求：

```java
MessagePayload payload = MessagePayload.text("这是回复")
        .replyTo(message)
        .put("msg_seq", 2);

if (message.isGroupMessage()) {
    bot.sendGroupMessage(message.groupOpenId(), payload);
} else if (message.isDirectMessage()) {
    bot.sendDirectMessage(message.guildId(), payload);
} else if (message.isChannelMessage()) {
    bot.sendChannelMessage(message.channelId(), payload);
} else {
    bot.sendPrivateMessage(message.userOpenId(), payload);
}
```

`replyTo` 会写入 `msg_id` 并把 `msg_seq` 初始化为 1。对同一消息多次回复时，应用必须递增 `msg_seq`。

## 回复非消息事件

```java
MessagePayload payload = MessagePayload.text("事件响应")
        .eventReplyTo(event);
```

它会写入 `event_id`。只有官方明确允许回复的事件才能使用。

## 扩展字段和原始载荷

API 新增字段时，可以直接扩展：

```java
MessagePayload payload = MessagePayload.text("内容")
        .put("is_wakeup", true)
        .put("msg_seq", 1);
```

复杂 JSON 使用 `set`：

```java
ObjectNode keyboard = new ObjectMapper().createObjectNode();
keyboard.put("id", "模板 ID");
MessagePayload payload = MessagePayload.markdown("内容")
        .set("keyboard", keyboard);
```

也可以从完整 JSON 对象创建：

```java
MessagePayload payload = MessagePayload.raw(objectNode);
```

调用 `raw` 时会深拷贝传入对象，`toJson()` 也返回副本。

## 成功结果

`MessageResponse` 提供：

- `id()`：平台返回的消息 ID；
- `timestamp()`：平台返回的时间字符串；
- `raw()`：完整响应 JSON。

## 错误处理

```java
bot.sendGroupMessage(groupOpenId, "内容")
        .whenComplete((response, error) -> {
            if (error != null) {
                error.printStackTrace();
                return;
            }
            System.out.println(response.id());
        });
```

OpenAPI 非 2xx 响应会转换为 `BotApiException`。它包含：

- `httpStatus()`
- `errorCode()`
- `traceId()`

频率限制、主动消息额度、被动回复有效期和内容审核都由 QQ 平台执行，框架不会自动规避或重试业务错误。
