# 发送与回复消息

所有发送方法返回 `CompletableFuture<MessageResponse>`。网络请求和平台业务错误会以异常完成，不应忽略 Future。

## 主动发送文本

```java
bot.sendPrivateMessage(userOpenId, "你好");
bot.sendGroupMessage(groupOpenId, "群消息");
```

## 发送 Markdown

```java
MessagePayload payload = MessagePayload.markdown("# 标题\n正文");
bot.sendPrivateMessage(userOpenId, payload);
```

是否能发送 Markdown 取决于机器人权限和平台策略。

## 被动回复

最简单的方式：

```java
bot.onMessage(message -> message.replyText("收到"));
```

或显式构造请求：

```java
MessagePayload payload = MessagePayload.text("这是回复")
        .replyTo(message)
        .put("msg_seq", 2);

if (message.isGroupMessage()) {
    bot.sendGroupMessage(message.groupOpenId(), payload);
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
