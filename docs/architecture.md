# 架构与线程模型

## 组件

| 组件 | 职责 |
| --- | --- |
| `QQBot` | 公共入口、监听器注册、消息发送和生命周期管理 |
| `BotConfig` | 不可变配置和构建期校验 |
| `AccessTokenManager` | 获取、缓存并在过期前刷新 Access Token |
| `HttpApiClient` | OpenAPI GET/POST、鉴权头和错误转换 |
| `GatewayClient` | WebSocket 状态、心跳、Session、重连和事件分发 |
| `WebhookServer` | HTTP 回调、地址验证、验签和 ACK |
| `QQEvent` | 协议级原始事件 |
| `QQMessageEvent` | 单聊和群聊消息的标准化视图 |
| `MessagePayload` | 可扩展的消息请求构造器 |

## 共享事件通道

WebSocket 和 Webhook 都会构造 `QQEvent`，再进入 `QQBot.dispatch`。因此应用层监听代码无需区分事件来自哪种传输方式。

同一 Bot 不应同时启用 WebSocket 和 Webhook 来订阅同一批事件，否则业务可能处理重复事件。平台接入模式和应用部署结构应保持一致。

## 线程模型

- Gateway 建连和 OpenAPI 异步包装使用每个 Bot 自有的固定 HTTP 执行器；
- Gateway 心跳与重连使用单线程守护调度器；
- JDK WebSocket 回调线程负责解析和事件分发；
- Webhook 使用缓存线程池，每个请求在线程池线程中处理；
- 监听器同步执行；
- 消息发送返回 Future，调用方负责观察成功或失败。

监听器不应进行长时间阻塞操作。建议结构：

```java
ExecutorService businessPool = Executors.newFixedThreadPool(8);

bot.onMessage(message -> businessPool.submit(() -> {
    // 幂等检查、数据库访问、调用业务服务
    handle(message);
}));
```

应用关闭时也应关闭自己的线程池。

## HTTP 执行器

OpenAPI 和 Gateway 建连阶段内部仍使用阻塞式 HTTP 调用，但这些调用不会进入 `ForkJoinPool.commonPool`，而是由每个 Bot 自有的固定线程池承载。线程名格式为 `astraqqbot-http-{shardId}-{workerId}`，默认最多创建 4 个工作线程，可通过 `httpExecutorThreads(int)` 调整。

专用执行器随 Bot 创建并在 `QQBot.close()` 时中断、关闭。固定线程数使并发上限和线程名称保持稳定，也避免阻塞请求引起公共线程池吞吐波动。

## 日志边界

每个 `QQBot` 持有独立的日志级别配置。传输层记录 Gateway、Webhook 和 OpenAPI 生命周期，事件分发层记录命名监听器的捕获、完成、失败和耗时。异步监听器只有在返回的 `CompletionStage` 完成后才会记录处理完成。

默认不记录事件原始载荷，也不会记录 AppSecret、Access Token、Authorization 请求头或 Webhook 签名。详见 [日志与调试](logging.md)。

## 向前兼容

框架只对常用消息字段做强类型映射，始终保留 `JsonNode raw`。官方增加字段时，应用可以先从原始 JSON 读取，不必等待框架发布新模型。

这不保证协议完全向前兼容：字段语义变化、鉴权变更、端点迁移或 Gateway 行为变化仍可能需要升级框架。

## 安全边界

- AppSecret 存储和注入由宿主应用负责；
- 框架不会把凭证明文写入日志；
- 覆盖 API URI 时，凭证会被发送到配置的目标，必须确保目标可信；
- Webhook 验签依赖收到的原始请求体；
- 业务数据持久化、权限控制和内容合规不属于框架职责。
