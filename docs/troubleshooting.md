# 故障排查

## `appId must not be blank` 或 `clientSecret must not be blank`

环境变量未注入或名称错误。先检查当前进程是否能读取：

```bash
printenv QQ_BOT_APP_ID
printenv QQ_BOT_CLIENT_SECRET
```

不要在排障截图和日志中公开 AppSecret。

## Access Token 获取失败

检查：

- AppID 和 AppSecret 是否属于同一个机器人；
- 机器人是否被封禁或删除；
- 服务器能否访问 `https://bots.qq.com`；
- 是否覆盖了错误的 `accessTokenUri`；
- 平台是否返回了 `err_code`、`message` 或 `trace_id`。

## WebSocket 一直无法 READY

检查 `onError` 输出，并重点核对：

- intents 是否获得平台权限；
- Gateway 返回的关闭码；
- 机器人是否只能连接沙箱；
- 系统时间和网络是否正常；
- 连接创建频率是否超过平台限制。

`startWebSocket().join()` 在收到 READY 前会等待；网络失败时框架会持续重连，而不是立即让 Future 失败。

## Webhook 地址验证失败

检查：

- 平台访问的是 HTTPS 公网地址；
- 反向代理路径与 `webhookPath` 完全一致；
- 代理是否将请求转发到正确端口；
- AppSecret 是否正确；
- 代理是否改写了 JSON body；
- QQ 平台允许的回调端口范围。

可在代理访问日志中确认是否收到 `User-Agent: QQBot-Callback` 的 POST，但不要记录敏感请求头和凭证。

## Webhook 返回 401

请求签名不通过。常见原因：

- AppSecret 不匹配；
- `X-Signature-Ed25519` 或 `X-Signature-Timestamp` 被代理移除；
- 代理、WAF 或中间件修改了原始 body；
- 请求并非 QQ 平台发送。

不要通过关闭验签解决该问题。

## 消息发送 Future 异常完成

展开 `CompletionException` 的 cause。若为 `BotApiException`，记录：

- HTTP 状态码；
- 平台错误码；
- Trace ID；
- 请求场景和目标类型，但不要记录 Access Token。

常见原因包括限频、消息内容违规、没有主动消息权限、`msg_id` 过期或重复使用相同的 `msg_id + msg_seq`。

## 收到重复消息

这是平台至少一次投递语义下可能出现的正常情况。使用 `messageId` 和 `msg_idx` 做幂等，不要只依赖内存布尔值。

## 程序在 READY 后退出

`startWebSocket()` 的 Future 在 READY 时完成，`join()` 随后返回。如果 `main` 没有其他非守护线程，进程会退出。由应用服务器、生命周期管理器、阻塞等待或其他合适机制保持主进程运行。
