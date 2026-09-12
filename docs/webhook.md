# Webhook 部署与安全

## 工作方式

内置 `WebhookServer` 基于 JDK `HttpServer`，处理：

- `op=13` 回调地址验证；
- 普通事件的 Ed25519 签名验证；
- `op=12` HTTP Callback ACK；
- 与 WebSocket 共用的 `QQEvent` 和 `QQMessageEvent` 分发流程。

默认监听：

```text
http://127.0.0.1:8080/qqbot/events
```

QQ 平台要求配置 HTTPS 回调地址。内置服务器不直接处理 TLS，生产环境应使用反向代理。

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

QQ 开放平台中填写：

```text
https://bot.example.com/qqbot/events
```

## 请求验签

普通回调必须携带：

- `X-Signature-Ed25519`
- `X-Signature-Timestamp`

框架按官方算法，用 AppSecret 重复截取为 32 字节 seed，派生 Ed25519 公钥，并验证 `timestamp + 原始 HTTP body`。验签失败返回 HTTP 401，事件不会进入监听器。

`op=13` 地址验证在官方示例中可能没有上述签名头，因此当前实现允许验证请求缺少签名；如果验证请求包含两个签名头，则仍会执行验签。

## 回调响应顺序

普通事件先返回：

```json
{"op":12}
```

随后完成事件路由并将监听器任务投递到各插件执行器。Webhook 请求线程不执行插件业务，也不等待业务完成；HTTP ACK、任务成功入队和业务最终成功是三个独立状态。

## 网络限制

- 只接受 POST；其他方法返回 405；
- 请求体上限为 2 MiB，超出返回 413；
- 非 JSON 对象返回 400；
- 验签失败返回 401；
- 内部异常尝试返回 500，并通过 `onError` 通知应用。

## 生产建议

- 仅在回环地址监听，让反向代理成为唯一入口；
- 使用可信 CA 签发的证书并自动续期；
- 不要在代理层修改请求 body，否则签名验证会失败；
- 为回调路径设置独立访问日志，但不要记录 AppSecret；
- 限制请求体大小和连接超时；
- 对业务处理实施去重、超时和隔离；
- 监控 4xx/5xx、验签失败和平台重试量。
