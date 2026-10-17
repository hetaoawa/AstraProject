# 插件开发

插件实现 `astra-api` 中的 `AstraPlugin`，无需依赖 Spring 或 QQ SDK。通过初始化脚本创建独立仓库，或在框架仓库执行 `scripts/new-plugin.ps1` / `new-plugin.sh`。两条路径见 [开发与发布](development.md)。

## 依赖与打包

`astra-api` 依赖使用 `provided`，公共类由宿主加载。插件自己的运行依赖应打包到插件 JAR，通常可使用 Maven Shade；排除 `astra-api`、宿主运行时、PF4J 和日志实现。

将插件 JAR 放入运行目录下的 `plugins/`，重启后加载。旧插件 API 不兼容当前框架，需要按新接口修改。

## 事件注解

| 注解 | 匹配内容 |
| --- | --- |
| `@OnMessage` | 消息事件 |
| `@OnNotice` | 通知和统一撤回事件 |
| `@OnRequest` | 请求事件 |
| `@OnMetaEvent` | READY、RESUMED 等元事件 |
| `@OnEvent` | 所有事件，包括 `RawEvent` |
| `@OnCommand` | 消息中的命令、别名和前缀 |
| `@OnKeyword` | 包含关键词的文本 |
| `@OnFullMatch` | 去除首尾空白后完全相同的文本 |
| `@OnRegex` | 正则 `find` 匹配 |

事件注解均有 `scenes`、`priority`、`block`。`priority` 默认为 100，数值越小越早执行；相同优先级按插件 ID 和稳定处理器 ID 排序。`block` 默认为 `false`。通知、请求、元事件和通用事件还可用 `types` 限定平台事件名。

处理器参数可使用对应事件类型和 `PluginContext`，命令处理器还可接受 `CommandArgs`。一个方法只能使用一个事件注解，参数的事件类型会缩小匹配范围。

同步方法可返回 `void` 或 `HandlerResult`，异步方法返回 `CompletionStage`。异步执行失败会调用 `onError`。返回 `HandlerResult.BLOCK`，或标有 `block = true` 的方法成功执行后，该事件不再传给后续普通处理器。

```java
public final class HelloPlugin implements AstraPlugin {
    @OnCommand(value = "hello", aliases = {"hi"})
    public CompletionStage<ApiResponse> hello(
            MessageEvent event, PluginContext context, CommandArgs args) {
        return context.reply(event, "你好 " + args.text());
    }
}
```

命令默认以 `/` 开头，匹配前去除文本首尾空白。`CommandArgs.text()` 保留命令后的参数正文，`arguments()` 按空白分词，`command()` 是实际匹配的命令或别名。框架不解析引号；复杂命令语法可由插件处理 `text()`。

## 上下文服务

| 服务 | 用途 |
| --- | --- |
| `messaging()` | 群聊、单聊、子频道和频道私信的发送、回复、撤回 |
| `api()` | 操作目录、通用请求、频道图片上传和预签名文件分片上传 |
| `directory()` | 资料查找、分页搜索、本地成员与关系、union 关联、插件 metadata |
| `history()` | 消息 ID 或索引查找、前后消息、方向过滤和分页查询 |
| `eventArchive()` | 按事件类型和接收时间查询完整平台报文 |
| `httpRoutes()` | 在应用端口注册多个方法、路径和异步处理函数 |
| `sessions()` | 等待下一条匹配回复、取消会话 |
| `tasks()` | 延时、固定周期和 Cron 任务，随插件在对应机器人上的启停管理 |
| `services()` | 类型化服务的注册与查询，以及点分权限节点注册 |
| `configuration()` | `astra.plugins.configuration` 下对应插件 ID 的配置 |

具体用法见 [功能索引](features.md)。

## 平台接口

`api().operations()` 返回完整操作目录，`call` 按操作 ID 调用，`raw` 调用相对 API 路径并统一鉴权。`multipart` 用于上传频道图片，`uploadPart` 向预签名 URL 发送 PUT 分片。

```java
return context.api().call(
    "v2_groups_group_openid_info.get",
    Map.of("group_openid", event.chat().id()),
    Map.of(), Map.of());
```

`astra-qq` 中的 `QqEndpoints` 提供操作 ID 常量。只依赖 `astra-api` 的插件可通过 `operations()` 查看目录，再使用字符串 ID。请求和响应 Map 保留平台字段及未识别的扩展字段，数组响应包装在 `items` 中。常用请求还可使用 `MessagePayload`、`Moderation` 和 `api().moderation()`，见 [消息与平台请求](messages.md#常用请求)。

接口能否使用取决于机器人的平台权限。`PlatformException` 保留平台错误码，查询失败会报告错误。成功查询会补充本地资料，但本地成员记录可能不完整。

分片上传依次调用 `upload_prepare`、`uploadPart`、`upload_part_finish`。框架不根据平台 `upload_config` 自动重试分片。队列请求最大为 16 MiB，包含 Base64 编码开销，大文件应分片上传。

流式单聊使用 `stream_messages` 操作 ID。插件保存首片返回的 `id`，按平台要求递增 `index`。发送限制见 [接口限速与配额](rate-limits.md)。

## 会话与任务

```java
context.sessions().await(
    event.chat(), event.actor(),
    next -> !next.message().text().startsWith("/"),
    Duration.ofSeconds(60),
    (next, ctx) -> ctx.reply(next, "收到").thenApply(x -> HandlerResult.CONTINUE),
    () -> { /* 到期清理 */ });
```

`await` 注册等待后返回，下一条匹配消息调用后续回调。当前处理器应直接结束，避免等待回复的 future 占用聊天执行通道。过滤器应快速完成，避免副作用。关闭返回的 Handle 或调用 `sessions.cancel` 可以取消等待。回调默认独占匹配到的回复。

`@Scheduled` 用于定时任务，`delayMillis`、`intervalMillis`、`cron` 三选一。默认时区 `zone` 为 `Asia/Shanghai`，同一任务不会重叠。方法接受零个参数或一个 `PluginContext`，可以返回 `CompletionStage`。

会话到期回调和定时任务异常也会调用 `onError`，此时事件参数可能为空。会话仅存内存；重启后临时会话和延时任务结束，周期任务由插件重新注册，不补跑停机期间的执行。

## 生命周期与依赖

`onEnable`、`onDisable` 按机器人调用。一个插件实例可以服务多个机器人，实例字段需要按机器人分别管理。不同聊天会并行调用同一插件，插件自身也需要处理并发访问。

启停状态保存到数据库，重启后保持。初始化时暂存会话、任务和 HTTP 路由，`onEnable` 完成后才开放事件入口并激活注册。初始化或提交失败会撤销活动状态、会话、任务、服务、权限节点和路由。初始化期间不要等待尚未激活的任务或 HTTP 回调。

停用按以下顺序处理：

1. 关闭该机器人上的事件入口和 HTTP 路由。
2. 取消任务、会话及尚未执行的平台请求。
3. 等待正在运行的处理器和 HTTP 调用结束。
4. 调用 `onDisable`。

停用必需依赖前，会先停用依赖它的插件。重新启用时，先启用依赖。即使全部停用，框架也不会替换或重新加载 JAR，更新仍需重启。

插件自行创建的线程、文件、连接和第三方任务需要在 `onDisable` 中释放。宿主无法安全终止任意失控的插件代码。

## Manifest

JAR Manifest 必须包含：

```text
Plugin-Id: my-plugin
Plugin-Version: 0.1.0
Plugin-Class: top.hetaoawa.astra.runtime.AstraPluginManager$BridgePlugin
Plugin-Requires: 0.1.0
Astra-Entrypoint: example.MyPlugin
Astra-Api-Version: 1
```

可选的 `Plugin-Dependencies` 使用 PF4J 依赖声明。API 主版本不兼容时，宿主拒绝加载。

## 权限服务

权限插件可以注册 `PermissionService`，处理主体、上下文和节点规则，也可以对接 LuckPerms 等系统。框架保存权限节点定义，授权判断由权限插件负责。
