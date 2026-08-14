# 事件与消息模型

框架提供三层事件接口，应用可按需要选择。

## 原始事件

```java
bot.onEvent(event -> {
    System.out.println(event.type());
    System.out.println(event.raw());
});
```

`QQEvent` 字段：

| 字段 | 说明 |
| --- | --- |
| `id()` | 最外层事件 ID |
| `op()` | Gateway/Webhook Opcode |
| `sequence()` | WebSocket 序列号；Webhook 可能为空 |
| `type()` | Dispatch 事件类型，例如 `C2C_MESSAGE_CREATE` |
| `data()` | 事件的 `d` 节点 |
| `raw()` | 完整原始 JSON |

`raw()` 和 `data()` 使用 Jackson `JsonNode`，可用于读取尚未建模的官方字段。

## 指定事件类型

```java
bot.onEvent("FRIEND_ADD", event -> {
    System.out.println("新增好友：" + event.data());
});
```

类型监听器先执行，随后执行全局原始事件监听器。

## 标准化消息事件

`onMessage` 目前接收以下事件：

- `C2C_MESSAGE_CREATE`
- `GROUP_AT_MESSAGE_CREATE`
- `GROUP_MESSAGE_CREATE`

```java
bot.onMessage(message -> {
    if (message.isGroupMessage()) {
        System.out.println("群 " + message.groupOpenId());
    } else {
        System.out.println("用户 " + message.userOpenId());
    }
    System.out.println(message.content());
});
```

`QQMessageEvent` 的主要字段：

| 字段 | 说明 |
| --- | --- |
| `eventId()` | 外层事件 ID |
| `eventType()` | 消息事件类型 |
| `messageId()` | 可用于被动回复的消息 ID |
| `author()` | 标准化的 `QQUser` |
| `content()` | 文本内容；非文本消息可能为空 |
| `userOpenId()` | 单聊用户 OpenID |
| `groupOpenId()` | 群 OpenID |
| `messageType()` | 官方消息类型整数 |
| `timestamp()` | 尝试解析后的 `Instant`，格式异常时为空 |
| `scene()` | 消息场景和扩展项 |
| `attachments()` | 图片、文件、语音等附件元数据 |
| `raw()` | 完整原始 JSON |

## 场景扩展

官方 `message_scene.ext` 使用 `key=value` 字符串。可通过辅助方法读取：

```java
String messageIndex = message.scene().extension("msg_idx");
String authToken = message.scene().extension("auth_token");
```

## 去重和幂等

平台可能重复投递相同消息。框架当前不保存消息状态，也不自动去重。应用应选择稳定键写入 Redis、数据库或本地缓存，并设置合理的过期时间。

对当前消息结构，至少考虑：

- `messageId()`；
- `scene().extension("msg_idx")`；
- 必要时组合 `eventType()` 和目标 OpenID。

监听器执行过程中抛出的异常会被记录，不会中断后续监听器。业务侧仍应使用 `onError` 和自己的可观测性系统记录失败。
