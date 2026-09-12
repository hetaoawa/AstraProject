# 命令监听器

命令监听器只处理符合 Bot 配置的消息。前缀和分隔符在 `BotConfig` 中统一配置：

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .commandPrefixes("/", "#", ".")
        .commandSeparator(" ")
        .build();
```

上面的配置会识别 `/help`、`#help` 和 `.help`。命令匹配不区分大小写；没有配置前缀的普通消息不会触发命令监听器。

## 注册命令

插件只需创建一次注册作用域，监听器名称会自动从插件名和命令生成：

```java
QQBot.Plugin plugin = bot.plugin("moderation");

plugin.onCommand("ban", 100, false, command -> {
    String target = command.argument(0);
    // 执行禁言逻辑
});
```

`onCommand` 参数依次为：命令名、优先级、是否继续传播、处理函数。异步处理使用：

```java
plugin.onCommandAsync("help", 10, true, command ->
        command.replyText("可用命令：/help /ban"));
```

## 命令拆分

对于消息：

```text
#ban user-123 10
```

框架会生成：

```text
prefix()     -> "#"
command()    -> "ban"
tokens()     -> ["ban", "user-123", "10"]
arguments()  -> ["user-123", "10"]
argument(0)  -> "user-123"
argument(1)  -> "10"
```

命令监听器可以通过 `message()` 取得原始 `QQMessageEvent`，也可以直接使用 `replyText`；目标标识可从 `userOpenId()`、`groupOpenId()`、`guildId()` 或 `channelId()` 获取。

## 优先级与传播

同一个命令按照优先级从高到低执行，优先级数值越大越先执行；优先级相同时按注册顺序执行。

```java
plugin.onCommand("help", 100, true, command -> {
    // 先执行，并允许下一个匹配监听器继续执行
});

plugin.onCommand("help", 10, false, command -> {
    // 执行后停止后续匹配的命令监听器
});
```

`continuePropagation=false` 只停止后续命令监听器；普通 `onMessage` 监听器仍会按原有规则接收消息。异步命令监听器返回的 `CompletionStage` 完成后，框架才会继续执行下一个命令监听器。

命令回调本身运行在所属插件的执行器中，Gateway/Webhook 分发线程不会等待命令完成。不同事件各自拥有命令链，可以并发推进；同一事件内匹配的命令监听器仍严格按照优先级和传播规则串行推进。若某个插件队列已满，该命令任务会失败并记录错误，随后仍按该监听器的 `continuePropagation` 配置决定是否继续。

命令监听器日志会包含自动生成的名称、命令和优先级，例如：

```text
[DEBUG] [PLUGIN] dispatching handler=moderation.command.ban kind=command command=ban priority=100
[DEBUG] [PLUGIN] captured handler=moderation.command.ban kind=command plugin=moderation queueSize=0
```
