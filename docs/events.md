# 事件与消息模型

框架提供原始事件、指定类型事件和标准化事件三种用法。常见业务优先使用标准化事件；需要读取新字段或尚未建模的事件时使用原始 JSON。

## 原始事件

```java
QQBot.Plugin plugin = bot.plugin("events");
plugin.onEvent(event -> {
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
plugin.onEvent("FRIEND_ADD", event -> {
    System.out.println("新增好友：" + event.data());
});
```

指定类型监听器会优先安排，全局原始事件监听器随后安排。回调可能并发运行，请勿依赖它们的开始或完成顺序。

## 标准化消息事件

`onMessage` 目前接收以下事件：

- `C2C_MESSAGE_CREATE`
- `GROUP_AT_MESSAGE_CREATE`
- `GROUP_MESSAGE_CREATE`
- `AT_MESSAGE_CREATE`
- `MESSAGE_CREATE`
- `DIRECT_MESSAGE_CREATE`

```java
plugin.onMessage(message -> {
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
| `guildId()` | 频道 ID；频道私信事件中为私信会话 Guild ID |
| `channelId()` | 文字子频道 ID |
| `sequence()` | 频道消息顺序号 |
| `member()` | 频道成员原始 JSON |
| `messageType()` | 官方消息类型整数 |
| `timestamp()` | 尝试解析后的 `Instant`，格式异常时为空 |
| `scene()` | 消息场景和扩展项 |
| `attachments()` | 图片、文件、语音等附件元数据 |
| `raw()` | 完整原始 JSON |

场景判断可使用 `isPrivateMessage()`、`isGroupMessage()`、`isChannelMessage()` 和 `isDirectMessage()`。`replyText` 会根据场景自动调用正确的发送端点。

## 场景扩展

官方 `message_scene.ext` 使用 `key=value` 字符串。可通过辅助方法读取：

```java
String messageIndex = message.scene().extension("msg_idx");
String authToken = message.scene().extension("auth_token");
```

## 去重和幂等

平台可能重复投递相同消息。应用必须自行去重，可将稳定键写入 Redis、数据库或本地缓存，并设置合理的过期时间。

对当前消息结构，至少考虑：

- `messageId()`；
- `scene().extension("msg_idx")`；
- 必要时组合 `eventType()` 和目标 OpenID。

同一插件的普通监听器可能并发运行。共享状态需要保证线程安全，业务写操作需要使用事件 ID 或消息 ID 保证幂等。监听器异常和容量不足会通过 Bot 错误通道报告，可使用 `onError` 统一处理。

## 扩展事件模型

除消息事件外，框架还提供以下标准化监听器：

```java
plugin.onInteraction(event -> event.respond(0));
plugin.onRelationship(event -> System.out.println(event.eventType()));
plugin.onMessageStatus(event -> System.out.println(event.status()));
plugin.onResource(event -> System.out.println(event.resourceId()));
```

| 监听器 | 模型 | 覆盖范围 |
| --- | --- | --- |
| `onInteraction` | `QQInteractionEvent` | `INTERACTION_CREATE`、Reaction 增删 |
| `onRelationship` | `QQRelationshipEvent` | 好友、群成员、机器人进退群、消息接收/拒绝状态 |
| `onMessageStatus` | `QQMessageStatusEvent` | 订阅消息状态和消息审核状态 |
| `onResource` | `QQResourceEvent` | Guild、Channel、成员、论坛和音频资源事件 |

标准化模型同时提供 `data()` 和 `raw()`。官方增加字段后，可以先从原始 JSON 中读取。
