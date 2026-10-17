# 消息历史

消息历史保存收到和发出的消息，支持按消息 ID、索引、成员、时间和方向查询。它与 [完整事件归档](events.md#开启报文归档) 分别配置。

## 开启历史

```yaml
astra:
  history:
    enabled: true
    retention-days: 30 # 0 表示长期保留
```

默认关闭。开启后记录新收到和发出的消息，无法补回关闭或停机期间的正文。附件只保存元信息，文件不会自动下载。历史关闭时，查询抛出 `HistoryDisabledException`。

## 查找记录与前后消息

```java
if (context.history().enabled()) {
    var record = context.history().findByIndex(
        context.bot(), event.chat(), event.message().index());
    var nearby = context.history().context(new History.ContextQuery(
        event.chat(), event.message().id(), null, event.actor(),
        10, 10, History.Direction.ANY));
    var inbound = context.history().search(new History.Query(
        context.bot(), event.chat(), event.actor(), null, null, null,
        History.Direction.INBOUND, 0, 20));
}
```

`findByIndex` 返回完整的 `MessageRecord`，包含消息 ID、正文、类型化内容、原始字段、时间、方向、处理状态和撤回来源。没有保存过该消息时返回空结果。查询范围按机器人、环境、场景和聊天区分。

`ContextQuery` 可通过 `messageId` 或 `index` 指定锚点。前后各可查询 0～500 条，结果按消息时间和稳定记录键排序。`before`、`after` 均按时间升序返回，不包含锚点。成员和方向条件只过滤前后记录，保留锚点本身。

| `History.Direction` | 查询范围 |
| --- | --- |
| `INBOUND` | 收到的消息 |
| `OUTBOUND` | 发出的消息 |
| `ANY` | 两个方向 |

分页 `Query` 默认不限方向。`recalled` 为 `null` 时，结果包含已撤回消息。索引别名和 TMP 的使用范围见 [消息索引](messages.md#消息索引)。

## 撤回记录

四种聊天场景的撤回 API 明确成功后，记录的 `recalled` 更新为 `true`，来源加入 `RecallSource.API_SUCCESS`。频道和频道私信的撤回通知加入 `PLATFORM_NOTICE`，重复通知只更新同一记录。两个来源可以共存。

撤回通知或 API 成功结果先于原消息到达时，框架先建立占位记录，收到原消息后补充正文。撤回后仍保留原文，保留期清理也适用于这些记录。

API 调用失败或结果不明时，撤回状态保持原值，请求不会自动重发。当前群聊和单聊接入没有适配撤回通知，这两种场景通过 API 成功结果更新本地状态。
