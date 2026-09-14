# 命令监听器

命令监听器从消息文本中解析命令名和参数。先在 `BotConfig` 中设置前缀和分隔符：

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .commandPrefixes("/", "#", ".")
        .commandSeparator(" ")
        .build();
```

以上配置可识别 `/help`、`#help` 和 `.help`。命令名匹配忽略大小写。多个前缀同时适配一条消息时，优先使用较长的前缀。

注册处理器：

```java
plugin.onCommand("ban", 100, false, command -> {
    String member = command.argument(0);
    moderation.ban(member);
    command.replyText("done");
});
```

`onCommand` 的四个参数依次为命令名、优先级、是否继续传播和处理器。数值较大的优先级先执行；相同优先级沿用注册顺序。处理器成功结束且 `continuePropagation=true` 时，下一个匹配处理器才会执行。异常、拒绝、取消和超时都会结束本次命令传播。

`QQCommandEvent` 提供：

| 方法 | 内容 |
| --- | --- |
| `command()` | 命令名 |
| `prefix()` | 本次匹配的前缀 |
| `tokens()` | 命令名和全部参数 |
| `arguments()` | 命令名之后的参数 |
| `argument(index)` | 指定位置的参数，从 0 开始 |
| `message()` | 原始 `QQMessageEvent` |
| `replyText(content)` | 回复触发命令的消息 |

普通 `onMessage` 监听器会独立处理同一条消息。`continuePropagation=false` 只控制命令处理器之间的传播。需要校验参数数量时，请先检查 `arguments().size()`，避免 `IndexOutOfBoundsException`。
