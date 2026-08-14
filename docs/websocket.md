# WebSocket 生命周期

## 连接流程

框架启动后的主要流程：

1. 通过 Access Token 调用 `/gateway/bot` 获取 WSS 地址；
2. 建立 WebSocket；
3. 收到 `op=10 Hello`，读取 `heartbeat_interval`；
4. 首次连接发送 `op=2 Identify`；
5. 收到 `READY`，保存 `session_id` 并完成 `startWebSocket()` 返回的 Future；
6. 按服务端周期发送 `op=1 Heartbeat`，携带最新序列号；
7. 断线后优先使用 `op=6 Resume`，会话无效时重新 Identify。

## Opcode 处理

| Opcode | 行为 |
| --- | --- |
| `0 Dispatch` | 转换为 `QQEvent` 并分发 |
| `1 Heartbeat` | 立即发送心跳响应 |
| `7 Reconnect` | 主动关闭当前连接并重连 |
| `9 Invalid Session` | 清除 Session 和序列号，重新 Identify |
| `10 Hello` | 创建心跳任务，发送 Identify 或 Resume |
| `11 Heartbeat ACK` | 确认心跳成功，不向业务层分发 |

## 重连策略

重连从 `reconnectInitialDelay` 开始，每次失败后翻倍，最大不超过 `reconnectMaxDelay`。成功建立网络连接后，等待时间恢复为初始值。

发生网络异常时会：

- 使用 `java.util.logging` 输出警告；
- 调用所有通过 `onError` 注册的错误监听器；
- 在 Bot 未关闭时安排下一次重连。

## Session 恢复

框架只在内存中保存 `session_id` 和最新序列号。进程重启后无法 Resume，会重新 Identify。若业务需要跨进程恢复，需要扩展持久化机制；当前公开 API 尚未暴露 Session 存储接口。

## 心跳边界

- 首次心跳携带 `d=null`；
- 收到 Dispatch 后更新最新 `s`；
- 心跳间隔以服务端 Hello 为准，最低按 1 秒处理；
- 关闭 Bot 会取消心跳任务和待执行的重连任务。

## 已知限制

- 一个 `QQBot` 实例只维护一个 Gateway 连接；
- 分片需要应用创建多个实例；
- Session 不持久化；
- 当前版本没有向业务层暴露连接状态机、心跳延迟和重连次数指标；
- 尚未使用真实机器人执行长期稳定性和故障注入测试。
