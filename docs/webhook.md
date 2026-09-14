# Webhook 部署指南

Webhook 适合已有 HTTP 服务入口、需要由 QQ 平台主动回调的部署方式。内置 `WebhookServer` 提供地址验证、Ed25519 签名校验和事件 ACK。

## 启动服务

默认监听地址为：

```text
http://127.0.0.1:8080/qqbot/events
```

可通过 `BotConfig` 修改：

```java
BotConfig config = BotConfig.builder()
        .appId(appId)
        .clientSecret(clientSecret)
        .webhookAddress("127.0.0.1", 8080)
        .webhookPath("/qqbot/events")
        .build();

QQBot bot = QQBot.create(config);
bot.onError(Throwable::printStackTrace);
WebhookServer server = bot.startWebhook();
```

QQ 开放平台要求公网回调地址使用 HTTPS。生产环境应让内置服务监听回环地址，并在前方配置反向代理。

## Nginx 示例

```nginx
server {
    listen 443 ssl http2;
    server_name bot.example.com;

    ssl_certificate     /etc/letsencrypt/live/bot.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bot.example.com/privkey.pem;

    location = /qqbot/events {
        proxy_pass http://127.0.0.1:8080/qqbot/events;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        client_max_body_size 2m;
    }
}
```

在 QQ 开放平台填写：

```text
https://bot.example.com/qqbot/events
```

## 安全要求

- 回调只接受 POST 请求。
- 请求体上限为 2 MiB。
- 普通事件必须携带 `X-Signature-Ed25519` 和 `X-Signature-Timestamp`。
- 反向代理必须原样转发请求体；压缩、重新编码或格式化 JSON 会导致验签失败。
- AppSecret 必须存放在密钥管理服务或受保护的环境变量中。
- 内置服务建议只监听 `127.0.0.1`，公网入口交给 HTTPS 反向代理。

地址验证请求可缺少签名头。它携带签名头时，框架仍会执行验签。

## 回调处理

框架会向普通事件返回：

```json
{"op":12}
```

ACK 表示回调已被接收。插件处理结果请通过业务状态、日志和 `onError` 观察。平台可能重试事件，涉及写库、支付、发货和通知时必须做好幂等。

## 生产检查清单

- 使用可信 CA 证书并配置自动续期；
- 限制回调路径、请求体大小和连接超时；
- 监控 4xx/5xx、验签失败、回调延迟和平台重试量；
- 避免在访问日志中记录请求体、AppSecret 和签名；
- 对消息 ID 或事件 ID 建立带过期时间的去重记录；
- 在发布前完成地址验证、错误签名、超大请求和重复事件测试。
