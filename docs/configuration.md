# 配置项

所有配置通过 `BotConfig.builder()` 构建，构建后的 `BotConfig` 不可变。

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `appId(String)` | 无 | QQ 开放平台 AppID，必填 |
| `clientSecret(String)` | 无 | QQ 开放平台 AppSecret，必填 |
| `apiBaseUri(String/URI)` | `https://api.bot.qq.com/` | OpenAPI 基础地址 |
| `accessTokenUri(String/URI)` | `https://bots.qq.com/app/getAppAccessToken` | Access Token 接口地址 |
| `intents(long)` | `Intents.PRIVATE_AND_GROUP` | Gateway 订阅位图 |
| `shard(int id, int count)` | `0, 1` | 当前实例的分片编号和总数 |
| `connectTimeout(Duration)` | 20 秒 | HTTP/WebSocket 建连超时 |
| `reconnectInitialDelay(Duration)` | 2 秒 | Gateway 首次重连等待时间 |
| `reconnectMaxDelay(Duration)` | 30 秒 | Gateway 指数退避上限 |
| `webhookAddress(String, int)` | `127.0.0.1:8080` | 内置 HTTP 服务器监听地址 |
| `webhookPath(String)` | `/qqbot/events` | Webhook 请求路径 |
| `userAgent(String)` | `AstraQQBot/0.1.0` | HTTP User-Agent |

## Intents

`Intents` 提供官方事件位的常量。第一阶段推荐：

```java
.intents(Intents.GROUP_AND_C2C_EVENT)
```

多个 intents 使用按位或：

```java
.intents(Intents.GROUP_AND_C2C_EVENT | Intents.INTERACTION)
```

订阅没有权限的 intent 可能导致 Gateway 拒绝连接。只订阅业务实际需要的事件。

## 分片

```java
.shard(0, 4)
```

代表当前实例是 4 个分片中的第 0 个。需要自动创建和管理全部分片时使用：

```java
try (QQBotCluster cluster = QQBotCluster.create(config, 4)) {
    cluster.onEvent(System.out::println);
    cluster.startWebSocket().join();
}
```

`QQBotCluster` 会为每个分片复制配置并设置正确的 `(shardId, shardCount)`，统一启动和关闭连接。

## 沙箱和代理环境

需要使用非默认 API 地址时，可以覆盖两个 URI：

```java
.apiBaseUri("https://example.invalid/")
.accessTokenUri("https://example.invalid/app/getAppAccessToken")
```

这也便于在集成测试中指向本地 Mock Server。URI 必须由可信配置提供，避免把凭证发送到未知地址。

## 参数校验

构建配置时会检查：

- AppID、AppSecret、Webhook host/path、User-Agent 不能为空；
- `shardCount` 必须大于 0，`shardId` 必须在有效范围内；
- Webhook 端口必须位于 `1..65535`；
- 所有 Duration 必须为正数；
- 最大重连等待时间不得小于初始等待时间。
