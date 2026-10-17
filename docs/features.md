# 功能索引

插件通过 `astra-api` 使用以下功能，无需依赖 Spring。注解、依赖和生命周期见 [插件开发](plugins.md)。

| 功能 | 常用入口 | 详细说明 |
| --- | --- | --- |
| 消息正文、引用和合并记录 | `Message.content()`、`Message.displayText()` | [消息与平台请求](messages.md) |
| 发送、回复、撤回和管理操作 | `messaging()`、`api().moderation()`、`MessagePayload` | [消息与平台请求](messages.md#常用请求) |
| 历史查找和前后消息 | `history().findByIndex()`、`context()`、`search()` | [消息历史](history.md) |
| 申请、成员变更和其他通知 | `RequestEvent`、`NoticeEvent`、`MetaEvent` | [事件与报文归档](events.md) |
| 完整平台报文 | `eventArchive().search()` | [事件与报文归档](events.md#开启报文归档) |
| 插件 HTTP 接口 | `httpRoutes().register()` | [插件 HTTP 路由](http.md) |

平台未提供的字段保留为 `null` 或空集合。原始字段可从消息、事件及相关类型的 `raw` 读取；完整接收报文需要开启事件归档。

消息历史和事件归档默认关闭，开关和保留期分别设置。数据库升级与定期清理见 [架构与执行规则](architecture.md#数据库升级与清理)，各项已有验证见 [验收记录](validation.md)。
