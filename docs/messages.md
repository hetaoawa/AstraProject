# Messages

High-level send and reply methods block synchronously and return `MessageResponse`:

```java
MessageResponse privateMessage = bot.sendPrivateMessage(userOpenId, "Hello");
MessageResponse reply = message.replyText("收到");
MessageResponse channel = bot.sendChannelMessage(channelId, MessagePayload.markdown("Hello"));
```

Transport and QQ platform failures are thrown to the caller. In a plugin callback, the framework therefore knows that the network operation is part of that managed task and can report failure correctly.

Channel and direct-message payload conversion, multipart image upload, reply IDs and message sequences retain their existing semantics. Use `MessagePayload` to construct text, Markdown, media, Ark, embed or raw bodies.

The framework cannot discover a third-party fire-and-forget asynchronous call. Prefer its synchronous API or explicitly wait for completion before the callback returns.
