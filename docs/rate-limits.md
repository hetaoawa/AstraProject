# 接口限速与配额

本页按 2026-11-17 的源码、内置配置和配置示例整理。表中的数值是框架采用的规则；部署机器人的实际权限、后台额度和外部配置需要另行确认。

`qq/rate-limits.tsv` 包含 98 个操作 ID：48 项设置固定接口频率，50 项没有固定 QPS 或 QPM。两项禁言操作共用同一 HTTP 路由，共有 97 个不同的方法和路径模板。各接口的官方文档链接见 [逐接口规则清单](#逐接口规则清单)，协议验证范围见 [QQ 协议接口覆盖](protocol-coverage.md)。

`PlatformApi.call`、`raw`、`multipart` 和 `Messaging` 共用路由识别与限额服务，`raw` 调用已知路由时仍适用这些规则。凭证获取和预签名 COS 上传单独处理，不计入这 98 个操作 ID。

## 默认配置

计数按 AppID 和环境分别保存。union 关联不会合并不同机器人的额度。内置 `astra.bots` 为空；添加机器人后采用以下默认值，配置示例使用相同数值。

| 配置项（`astra.bots[].`） | 默认值／示例值 | 作用 |
| --- | --- | --- |
| `rate-limits.certification` | `UNVERIFIED` | 按未认证 bot 的主动消息额度执行 |
| `rate-limits.concurrency` | `8` | 单 bot 在途 HTTP 请求上限；合法配置范围 1–128；用于并发控制 |
| `rate-limits.day-zone` | `Asia/Shanghai` | 本地每日额度按该时区次日 00:00 重置 |
| `rate-limits.cooldown` | `1s` | 平台限流反馈后的最短冷却时间，与 Retry-After 取更晚结束时间 |
| `rate-limits.budgets` | `[]` | 没有额外的全局 QPS、每日调用总额或自定义预算 |
| `outbound-capacity` | `1000` | 单 bot 出站队列容量，包含排队及未确认任务 |

示例中的全局每秒 `limit: 100` 和全局每日 `limit: 10000` 均为注释，没有生效。入站 `concurrency: 8` 控制并行处理的聊天数，`queue-capacity: 10000` 控制接收队列容量。它们与出站接口限速分别设置。

旧的 `sends-per-second` 配置已移除，继续填写会报错。`certification` 应与后台认证状态一致；认证只改变主动消息的 bot 额度，接口 QPS、QPM 保持原规则。

## 固定接口频率

数值来自 [rate-limits.tsv](../astra-qq/src/main/resources/qq/rate-limits.tsv)。具体操作 ID、方法和路径见本页末尾清单。

| 设置 | 操作 ID 数 | 涉及接口 |
| --- | ---: | --- |
| 100 次/秒 | 2 | 群聊发送、单聊发送 |
| 50 次/秒 | 13 | 子频道删/查/改/创建及列表，频道详情，当前 机器人信息及频道列表，交互回执，分享链接，群/单聊媒体上传，单聊流式发送 |
| 10 次/秒 | 7 | 用户基本信息查询，群/单聊分片上传准备与完成，群/单聊撤回 |
| 60 次/分钟 | 11 | 入群审批，群黑名单添加，群成员列表，禁言设置，入群策略列表/创建/删除/修改/执行/白名单，指令面板目标设置 |
| 30 次/分钟 | 10 | 批量移出群成员，群 bot 状态，群资料，入群申请列表，群黑名单查询，单个群成员资料，禁言设置查询，菜单查询，指令面板列表/详情 |
| 10 次/分钟 | 3 | 指令面板创建/修改/删除 |
| 5 次/分钟 | 1 | 菜单设置 |
| 令牌桶补充 2 次/分钟、容量 10；另有 10 次/分钟窗口 | 1 | `GET /gateway` |
| 未设置固定接口 QPS/QPM | 50 | 包含四项素材管理接口；仍可能适用消息额度、并发、冷却和配置预算 |

固定频率使用滑动窗口，在同一 bot 环境内按 HTTP 方法和路径模板合计。不同群共享群发送接口的 100 次/秒，单聊发送单独计算。同一路由的操作别名也共享配置预算。每个请求需要满足全部适用规则。

`GET /gateway` 还使用令牌桶：初始容量 10，每 30 秒补充 1 个令牌，并限制任意 60 秒内最多 10 次。每分钟补充 2 个令牌，仍可使用桶内余额突发发送。`GET /gateway/bot` 没有配置这项固定 HTTP 频率。

## 消息额度与回复条件

框架按请求正文判断主动或被动消息：非空 `msg_id` 或 `event_id` 表示被动请求，单聊 `is_wakeup=true` 优先按互动召回处理。

`Messaging.send` 在群聊和单聊中会先尝试复用当前聊天的有效消息 ID，成功预留后按被动消息计数，不扣主动额度。ID 过期或次数用尽时才使用主动消息。`Messaging.reply` 始终使用指定的原消息，两种入口共享每个 ID 的回复次数。规则与失败处理见 [自动使用被动回复](messages.md#自动使用被动回复)。

| 类别 | 限制 | 统计范围／条件 |
| --- | --- | --- |
| 未认证主动单聊 | 5 次/秒 且 30 次/分钟 | bot 环境内全部单聊合计 |
| 未认证主动群聊 | 30 次/分钟 | bot 环境内全部群聊合计；没有额外群主动每秒窗口 |
| 主动单聊的单个用户 | 20 次/分钟 **且** 1000 次/日 | bot 环境 + 单聊目标用户 |
| 主动群聊的单个群 | 20 次/分钟 **且** 1000 次/日 | bot 环境 + 群目标；与单聊预算分开 |
| 单聊被动回复数量 | 4 次 | bot 环境 + 单聊目标 + `msg_id`；限额记录从首次获准尝试起保留 60 分钟 |
| 群聊被动回复数量 | 5 次 | bot 环境 + 群目标 + `msg_id`；限额记录从首次获准尝试起保留 5 分钟 |
| 群聊 @全体成员 | 1 次/日 | bot 环境 + 群目标；文本与 Markdown、主动与被动共享次数，权限由平台检查 |
| `Messaging.reply` 单聊时效 | 原消息时间起 60 分钟 | 排队前及发送前检查；群聊和单聊均分配回复序号，分别最多 5/4 次 |
| `Messaging.reply` 群聊／频道／频道私信时效 | 原消息时间起 5 分钟 | 频道及频道私信没有本地每条原消息的回复次数上限 |
| 仅带 `event_id` 的被动请求 | 无本地每事件回复次数额度 | 仍受接口频率等限制；不进入主动额度分支 |
| 单聊流式新消息 | 接口 50 次/秒 + 对应单聊业务额度 | `stream_msg_id` 为空时视为新消息，不按片序号判断 |
| 单聊流式续片 | 接口 50 次/秒 | `stream_msg_id` 非空时跳过主动/被动次数/召回额度；仍受额外配置预算 |
| 群／单聊媒体上传后代发 | 上传接口 50 次/秒 + 对应消息业务额度 | `srv_send_msg=true`；按正文区分主动、被动或单聊召回 |
| 群／单聊仅上传媒体 | 上传接口 50 次/秒 | `srv_send_msg` 不为 true；不扣消息业务额度 |
| 频道消息，主动／被动合计 | 每子频道 5 次/秒 | `POST /channels/{channel_id}/messages` |
| 频道主动消息条数 | 每子频道 `channel_push_max_num` 次/日；字段缺失时 20 | 成功查询所属频道消息设置后执行；值为 0 会拒绝主动发送 |
| 频道每日主动发送子频道数 | 无 `channel_ids` 白名单时 2 个；有白名单时为白名单长度 | 按所属频道统计当天使用过的不同子频道；非空白名单之外的目标拒绝发送 |
| 频道主动消息禁用 | `disable_push_msg=true` 时拒绝 | 消息设置成功结果缓存 5 分钟；查询失败不当作默认放行 |
| 频道私信主动消息 | 每用户 2 次/日 **且** 每 bot 环境合计 200 次/日 | 已观察到的实际接收用户，同一用户不同私信会话合并计数；映射未知时拒绝 |
| 日程创建 | bot 环境合计 10 次/日 **且** 每所属频道 100 次/日 | 管理员预算按该 bot 跨所有频道合计 10 次计算 |
| 频道接口授权链接 | 每频道 3 次/日 | bot 环境 + `guild_id`；当前是代码固定上限 |

被动次数保留期与原消息时效分别检查。`Messaging.reply` 在入队前分配序号，失败或取消后不返还。高级 `raw`、`call` 需要调用者提供 `msg_seq` 和原消息时间，并自行处理回复时效。

认证改为 `PERSONAL` 或 `ENTERPRISE` 后，主动单聊 bot 额度为 10 次/秒，取消未认证状态的 30 次/分钟 bot 窗口；主动群聊 bot 额度改为 60 次/分钟。单个用户或群的 20 次/分钟、1000 次/日保持不变。

### 频道与频道私信

发送频道主动消息前，框架确定所属频道并查询 `/guilds/{guild_id}/message/setting`，使用实际返回的 `channel_push_max_num`、`channel_ids`、`disable_push_msg`。成功结果缓存 5 分钟，子频道与频道映射来自接收事件或频道查询。文档示例中的 12 条不作为默认额度。

设置禁止发送、目标不在允许列表或缺少额度上下文时，调用返回错误。频道设置查询无权限时保留平台错误，插件可先查询确认授权。

频道私信按实际用户合并不同私信会话的每日计数。映射来自私信事件或成功的 `POST /users/@me/dms`，后者可经 `raw` 调用。映射未知时返回 `DM_RECIPIENT_UNKNOWN`。不同 bot 的平台标识不能直接用来推断用户关联。

### 单聊互动召回

以最近观察到的单聊消息为锚点，按 `day-zone` 的日期差划分区间，每段对该用户最多召回一次：

| 日期差 | 上限 |
| --- | --- |
| 0 天（同一日历日期） | 1 次 |
| 1–2 天 | 整段 1 次 |
| 3–6 天 | 整段 1 次 |
| 7–29 天 | 整段 1 次 |
| 不足以确定互动、负日期差或至少 30 天 | 拒绝召回 |

区间为当天、[1,3)、[3,7)、[7,30) 天，按日历日期计算。新观察到的互动会更新锚点。该分支跳过普通主动消息的 bot 与目标分钟窗口、1000 次/日额度，仍受接口频率和额外配置预算限制。区间及规则叠加方式需要与实际平台行为核对，最终资格由平台校验。

## 其他请求限制

| 项目 | 行为 |
| --- | --- |
| HTTP 并发 | 单 bot 环境合计最多 8 个在途请求，限流等待不占用该额度 |
| 出站执行通道 | 同 bot 环境、场景、聊天的消息串行；其他队列任务按 HTTP 方法 + 操作 + 目标串行 |
| 预签名 COS PUT | 未设固定 QPS/QPM；队列中同 bot 的所有上传共用一个通道，串行执行；也受 HTTP 并发和冷却限制 |
| 凭证获取 | 未设固定 QPS/QPM；每 bot 的 TokenManager 同步获取并缓存，缓存命中不产生调用或扣额 |
| 凭证刷新时间 | `expires_in > 120` 时提前 60 秒，否则在 TTL 的一半刷新，最小等待 1 秒；缺失 TTL 时按 7200 秒处理 |
| WebSocket IDENTIFY 频率 | 同一网关实例的各分片共享至少 **5.5 秒**间隔；未按 `max_concurrency` 建立并行桶 |
| WebSocket 会话创建额度 | 使用 `/gateway/bot` 返回的 `session_start_limit.remaining` 和 `reset_after`；缺失时分别采用 Long.MAX_VALUE 和 24 小时；会话预算在内存维护 |
| 平台反馈冷却 | HTTP 429 或下述业务码，暂停该 bot 环境的对应操作；未知路由按 HTTP 方法 + 具体路径暂停 |
| 冷却结束时间 | 默认当前时间 + 1 秒、Retry-After 指定时间、已有冷却结束时间三者取最大值；支持秒数及 HTTP 日期 |
| 失败调用重发 | 不自动重发 |
| 所有 OpenAPI 共用的固定 QPS／每日调用总额 | 当前未配置；`budgets: []` |

代码识别的冷却业务码为：`100001`、`40034100`、`20028`、`304045`、`304049`、`304050`、`304052`、`503004`、`610013`、`620006`、`1100100`、`1100308`。这些码不一定包含明确的频率、目标范围或重置时间。

HTTP 429 或上述业务码会暂停当前 bot 环境中对应操作的后续调用。无法确认用户或群范围时，暂停整个操作；其他接口继续运行。冷却结束时间取 `Retry-After`、配置的 `cooldown` 和已有结束时间中的最晚值，刚失败的请求不会重发。错误解析保留 `err_code` 或旧 `code`、`trace_id` 和响应头。参考 [调用与错误指南](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/api-call-guide.html)。

凭证使用 `TokenManager` 缓存，刷新时间见上表；命中缓存时没有 HTTP 调用，也不扣额度。参考 [凭证文档](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html)。

## 额外预算

`rate-limits.budgets` 可以添加后台分配的额度，只能收紧内置规则。框架没有为所有 OpenAPI 配置统一固定 QPS 或每日调用总额。示例见 [application.example.yml](../config/application.example.yml)。

每项填写唯一 `id`、`operation`、`scope`、`limit`，并选择 `window` 或 `daily: true`：

| 字段 | 含义 |
| --- | --- |
| `operation` | 操作 ID 或 `*`；相同路由的操作别名共享预算 |
| `scope: BOT` | 当前 bot 环境的全部匹配请求 |
| `scope: TARGET` | 按第一个路径参数计算；没有路径参数时使用 bot |
| `window` | 秒或分钟等短期窗口 |
| `daily: true` | 使用配置时区的日界线，忽略窗口参数 |

`operation: "*"` 包含 OpenAPI、未知通用调用和实际凭证获取，不包含预签名 COS PUT。COS 上传可单独设置 `operation: upload_part`。

认证状态、HTTP 并发、时区、冷却、额外预算和出站队列容量可配置。48 项接口频率来自资源表，其他固定业务额度来自代码，当前没有逐项覆盖内置上限的 YAML 配置。

## 执行、计数与恢复

消息按聊天排队，其他接口按操作和目标分别执行。同一聊天的流式首片与续片保持顺序。限速等待不占 HTTP 并发额度，其他通道可继续；队列容量直到消费确认后才释放。等待时检查取消和回复时效，Redis 故障时暂停，恢复后重新计算当天额度。

获准发送前才将任务置为 `STARTED`。重启可继续尚未开始的任务，已开始但结果不明的任务不会重跑。

每日计数、每日使用子频道集合、回复额度、短期窗口和冷却保存在 V4 数据表中，可复用 ID 和共享序号保存在 V5 数据表中，三种数据库采用相同接口。短期窗口有容量受限的内存读取缓存，数据库写入成功后才放行。重启不清零；WebSocket 会话预算在启动后根据网关响应重新初始化。

本地每日额度默认按 `Asia/Shanghai` 午夜重置，可用 `day-zone` 修改。短期频率或并发不足时等待；每日额度或回复次数用尽时返回 `RateLimitException`，包含规则说明和 `retryAt`，不会自动等到次日发送。

计数在获准尝试时扣除。失败、发送边界上的取消和结果不明均不自动返还。清理只删除过期计数。频道、日程等本地统计仅包含当前 bot 环境通过本框架发出的调用，其他管理员或外部程序消耗的总额度仍由平台判断。

## 官方文档与待确认事项

下表保留业务额度的文档入口。这里整理本地规则和已有来源，没有重新核验全部官网页面。

| 规则 | 范围 | 默认额度 | 来源 |
| --- | --- | --- | --- |
| 主动单聊，未认证 | bot | 5/秒且30/分钟 | [消息概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html) |
| 主动单聊，个人/企业认证 | bot | 10/秒 | 同上 |
| 主动群聊，未认证 / 已认证 | bot | 30/分钟 / 60/分钟 | 同上 |
| 主动单聊 / 群聊 | 单个用户 / 群 | 20/分钟且1000/日 | 同上 |
| 频道发送，不分主动/被动 | 子频道 | 5/秒 | [频道发送](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html) |
| 频道主动消息 | 子频道、所属频道 | 未设置时20/日、每日最多2个子频道 | 同上及 [频道设置](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/setting/message_setting.html) |
| 频道私信主动消息 | 用户及bot | 2/用户/日，bot共200/日 | [频道私信](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html) |
| 日程创建 | 当前管理员bot、频道 | 10/管理员/日，100/频道/日 | [日程创建](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/post_schedule.html) |
| 频道授权链接 | bot所在频道 | 默认3/日 | [授权链接](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/api_permissions/post_api_permission_demand.html) |
| 普通被动回复 | bot、场景、聊天、原消息 | 单聊4次，群聊5次 | 消息概述 |
| 互动召回 | 单聊用户最近观察到的互动及周期 | 每周期1条 | 消息概述 |

上线前应确认接口 QPS 的范围、滑动窗口与 gateway 令牌桶算法、每日重置时间、失败是否扣额、`event_id` 回复次数、流式续片判定、召回区间与主动额度叠加、频道白名单和每日子频道数量、日程管理员额度范围，以及 5.5 秒串行 Identify 与 `max_concurrency` 的关系。这些属于框架采用的实现方式或默认值，可能与账号实际规则不同。

实现位置：[配置默认值](../astra-runtime/src/main/java/top/hetaoawa/astra/runtime/AstraConfig.java)、[限速实现](../astra-qq/src/main/java/top/hetaoawa/astra/qq/OutboundLimits.java)、[回复时效与序号](../astra-qq/src/main/java/top/hetaoawa/astra/qq/QqMessaging.java)、[凭证缓存](../astra-qq/src/main/java/top/hetaoawa/astra/qq/TokenManager.java)、[WebSocket 会话限制](../astra-qq/src/main/java/top/hetaoawa/astra/qq/QqGateway.java)。

## 逐接口规则清单

下表“未设固定频率”表示资源表没有为该操作设置基础接口 QPS 或 QPM。适用的业务额度、并发、冷却和额外预算仍会生效。官网链接供逐项对照，实际账号额度需在部署时确认。

| 操作 ID | 方法及路径 | 固定接口频率 | 其他预算／条件 | 官方文档 |
| --- | --- | --- | --- | --- |
| `channels_channel_id.delete` | `DELETE /channels/{channel_id}` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.delete.html) |
| `channels_channel_id.get` | `GET /channels/{channel_id}` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.get.html) |
| `channels_channel_id.patch` | `PATCH /channels/{channel_id}` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.patch.html) |
| `gateway.get` | `GET /gateway` | 2/分钟；突发10/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/gateway.get.html) |
| `guilds_guild_id_channels.get` | `GET /guilds/{guild_id}/channels` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id_channels.get.html) |
| `guilds_guild_id.get` | `GET /guilds/{guild_id}` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id.get.html) |
| `users_me.get` | `GET /users/@me` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/users_me.get.html) |
| `interactions_interaction_id.put` | `PUT /interactions/{interaction_id}` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/interactions_interaction_id.put.html) |
| `users_me_guilds.get` | `GET /users/@me/guilds` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/users_me_guilds.get.html) |
| `v2_generate_url_link.post` | `POST /v2/generate_url_link` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_generate_url_link.post.html) |
| `guilds_guild_id_channels.post` | `POST /guilds/{guild_id}/channels` | 50/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id_channels.post.html) |
| `v2_groups_group_id_upload_part_finish.post` | `POST /v2/groups/{group_id}/upload_part_finish` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_part_finish.post.html) |
| `v2_groups_group_id_upload_prepare.post` | `POST /v2/groups/{group_id}/upload_prepare` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_prepare.post.html) |
| `v2_groups_group_openid_approval_join_request_member_openid.post` | `POST /v2/groups/{group_openid}/approval_join_request/{member_openid}` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_approval_join_request_member_openid.post.html) |
| `v2_groups_group_openid_batch_remove_members.post` | `POST /v2/groups/{group_openid}/batch_remove_members` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_batch_remove_members.post.html) |
| `v2_groups_group_openid_bot_state.get` | `GET /v2/groups/{group_openid}/bot_state` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_bot_state.get.html) |
| `v2_groups_group_openid_files.post` | `POST /v2/groups/{group_openid}/files` | 50/秒 | srv_send_msg=true时叠加对应消息预算 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_files.post.html) |
| `v2_groups_group_openid_info.get` | `GET /v2/groups/{group_openid}/info` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_info.get.html) |
| `v2_groups_group_openid_join_request_list.get` | `GET /v2/groups/{group_openid}/join_request_list` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_join_request_list.get.html) |
| `v2_groups_group_openid_member_blacklist.post` | `POST /v2/groups/{group_openid}/member_blacklist` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_member_blacklist.post.html) |
| `v2_groups_group_openid_member_blacklist.get` | `GET /v2/groups/{group_openid}/member_blacklist` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_member_blacklist.get.html) |
| `v2_groups_group_openid_members.get` | `GET /v2/groups/{group_openid}/members` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_members.get.html) |
| `v2_groups_group_openid_messages.post` | `POST /v2/groups/{group_openid}/messages` | 100/秒 | 群主动/被动 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html) |
| `v2_groups_group_openid_members_member_openid.get` | `GET /v2/groups/{group_openid}/members/{member_openid}` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_members_member_openid.get.html) |
| `v2_groups_group_openid_messages_message_id.delete` | `DELETE /v2/groups/{group_openid}/messages/{message_id}` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages_message_id.delete.html) |
| `v2_groups_group_openid_restrict_chat_setting.post` | `POST /v2/groups/{group_openid}/restrict_chat_setting` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_restrict_chat_setting.post.html) |
| `v2_groups_join_approval_strategy.get` | `GET /v2/groups/join_approval_strategy` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy.get.html) |
| `v2_groups_join_approval_strategy.post` | `POST /v2/groups/join_approval_strategy` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy.post.html) |
| `v2_groups_group_openid_restrict_chat_setting.get` | `GET /v2/groups/{group_openid}/restrict_chat_setting` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_restrict_chat_setting.get.html) |
| `v2_groups_join_approval_strategy_strategy_id.delete` | `DELETE /v2/groups/join_approval_strategy/{strategy_id}` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id.delete.html) |
| `v2_groups_join_approval_strategy_strategy_id.patch` | `PATCH /v2/groups/join_approval_strategy/{strategy_id}` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id.patch.html) |
| `v2_groups_join_approval_strategy_strategy_id_execute.post` | `POST /v2/groups/join_approval_strategy/{strategy_id}/execute` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id_execute.post.html) |
| `v2_groups_join_approval_strategy_strategy_id_whitelist_users.post` | `POST /v2/groups/join_approval_strategy/{strategy_id}/whitelist_users` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id_whitelist_users.post.html) |
| `v2_menu.get` | `GET /v2/menu` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_menu.get.html) |
| `v2_menu.put` | `PUT /v2/menu` | 5/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_menu.put.html) |
| `v2_panels.get` | `GET /v2/panels` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels.get.html) |
| `v2_panels_panel_id.delete` | `DELETE /v2/panels/{panel_id}` | 10/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.delete.html) |
| `v2_panels_panel_id.get` | `GET /v2/panels/{panel_id}` | 30/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.get.html) |
| `v2_panels.post` | `POST /v2/panels` | 10/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels.post.html) |
| `v2_users_user_id_upload_prepare.post` | `POST /v2/users/{user_id}/upload_prepare` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_prepare.post.html) |
| `v2_panels_panel_id.put` | `PUT /v2/panels/{panel_id}` | 10/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.put.html) |
| `v2_users_user_id_upload_part_finish.post` | `POST /v2/users/{user_id}/upload_part_finish` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_part_finish.post.html) |
| `v2_users_user_openid_files.post` | `POST /v2/users/{user_openid}/files` | 50/秒 | srv_send_msg=true时叠加对应消息预算 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_files.post.html) |
| `v2_panels_panel_id_target.put` | `PUT /v2/panels/{panel_id}/target` | 60/分钟 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id_target.put.html) |
| `v2_users_user_openid_messages_message_id.delete` | `DELETE /v2/users/{user_openid}/messages/{message_id}` | 10/秒 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages_message_id.delete.html) |
| `v2_users_user_openid_messages.post` | `POST /v2/users/{user_openid}/messages` | 100/秒 | 单聊主动/被动/召回 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html) |
| `v2_users_user_openid_stream_messages.post` | `POST /v2/users/{user_openid}/stream_messages` | 50/秒 | stream_msg_id为空计单聊业务额度，非空仅计接口及配置预算 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_stream_messages.post.html) |
| `get_guild_api_permission` | `GET /guilds/{guild_id}/api_permission` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/api_permissions/get_guild_api_permission.html) |
| `delete_guild_announces` | `DELETE /guilds/{guild_id}/announces/{message_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/announces/delete_guild_announces.html) |
| `post_api_permission_demand` | `POST /guilds/{guild_id}/api_permission/demand` | 未设固定频率 | 频道默认3/日 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/api_permissions/post_api_permission_demand.html) |
| `audio_control` | `POST /channels/{channel_id}/audio` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/audio_control.html) |
| `delete_mic` | `DELETE /channels/{channel_id}/mic` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/delete_mic.html) |
| `delete_thread` | `DELETE /channels/{channel_id}/threads/{thread_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/delete_thread.html) |
| `post_guild_announces` | `POST /guilds/{guild_id}/announces` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/announces/post_guild_announces.html) |
| `put_mic` | `PUT /channels/{channel_id}/mic` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/put_mic.html) |
| `get_threads_list` | `GET /channels/{channel_id}/threads` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/get_threads_list.html) |
| `get_thread` | `GET /channels/{channel_id}/threads/{thread_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/get_thread.html) |
| `put_thread` | `PUT /channels/{channel_id}/threads` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/put_thread.html) |
| `get_pins_message` | `GET /channels/{channel_id}/pins` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/get_pins_message.html) |
| `delete_pins_message` | `DELETE /channels/{channel_id}/pins/{message_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/delete_pins_message.html) |
| `delete_schedule` | `DELETE /channels/{channel_id}/schedules/{schedule_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/delete_schedule.html) |
| `put_pins_message` | `PUT /channels/{channel_id}/pins/{message_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/put_pins_message.html) |
| `get_schedule` | `GET /channels/{channel_id}/schedules/{schedule_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/get_schedule.html) |
| `get_schedules` | `GET /channels/{channel_id}/schedules` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/get_schedules.html) |
| `patch_schedule` | `PATCH /channels/{channel_id}/schedules/{schedule_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/patch_schedule.html) |
| `post_schedule` | `POST /channels/{channel_id}/schedules` | 未设固定频率 | 管理员10/日、频道100/日 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/post_schedule.html) |
| `dms.delete.dms_guild_id_messages_message_id` | `DELETE /dms/{guild_id}/messages/{message_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html) |
| `dms.post.dms_guild_id_messages` | `POST /dms/{guild_id}/messages` | 未设固定频率 | 用户2/日、bot200/日（主动） | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html) |
| `recall` | `DELETE /channels/{channel_id}/messages/{message_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/recall.html) |
| `send` | `POST /channels/{channel_id}/messages` | 未设固定频率 | 子频道5/秒；主动每日及子频道集合；实际设置 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html) |
| `get_channel_roles_permissions` | `GET /channels/{channel_id}/roles/{role_id}/permissions` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/get_channel_roles_permissions.html) |
| `get_channel_permissions` | `GET /channels/{channel_id}/members/{user_id}/permissions` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/get_channel_permissions.html) |
| `put_channel_roles_permissions` | `PUT /channels/{channel_id}/roles/{role_id}/permissions` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/put_channel_roles_permissions.html) |
| `put_channel_permissions` | `PUT /channels/{channel_id}/members/{user_id}/permissions` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/put_channel_permissions.html) |
| `get_guild_roles` | `GET /guilds/{guild_id}/roles` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/get_guild_roles.html) |
| `delete_guild_member_role` | `DELETE /guilds/{guild_id}/members/{user_id}/roles/{role_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/delete_guild_member_role.html) |
| `post_guild_role` | `POST /guilds/{guild_id}/roles` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/post_guild_role.html) |
| `delete_guild_role` | `DELETE /guilds/{guild_id}/roles/{role_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/delete_guild_role.html) |
| `patch_guild_role` | `PATCH /guilds/{guild_id}/roles/{role_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/patch_guild_role.html) |
| `put_guild_member_role` | `PUT /guilds/{guild_id}/members/{user_id}/roles/{role_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/put_guild_member_role.html) |
| `get_online_nums` | `GET /channels/{channel_id}/online_nums` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/get_online_nums.html) |
| `get_member` | `GET /guilds/{guild_id}/members/{user_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_member.html) |
| `get_members` | `GET /guilds/{guild_id}/members` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_members.html) |
| `delete_member` | `DELETE /guilds/{guild_id}/members/{user_id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/delete_member.html) |
| `get_role_members` | `GET /guilds/{guild_id}/roles/{role_id}/members` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_role_members.html) |
| `patch_guild_member_mute` | `PATCH /guilds/{guild_id}/members/{user_id}/mute` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_member_mute.html) |
| `patch_guild_mute_multi_member` | `PATCH /guilds/{guild_id}/mute` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_mute_multi_member.html) |
| `message_setting` | `GET /guilds/{guild_id}/message/setting` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/setting/message_setting.html) |
| `patch_guild_mute` | `PATCH /guilds/{guild_id}/mute` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_mute.html) |
| `gateway_bot.get` | `GET /gateway/bot` | 未设固定频率 | 身份创建采用session_start_limit | [文档](https://bot.q.qq.com/wiki/develop/api-v2/openapi/wss/shard_url_get.html) |
| `channel_reactions.put` | `PUT /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) |
| `channel_reactions.delete` | `DELETE /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) |
| `channel_reactions.get` | `GET /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) |
| `v2_media_images_prepare.post` | `POST /v2/media-images/prepare` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) |
| `v2_media_images_complete.post` | `POST /v2/media-images/complete` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) |
| `v2_media_images.get` | `GET /v2/media-images` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) |
| `v2_media_images_image_id_delete.delete` | `DELETE /v2/media-images/{image_id}/delete` | 未设固定频率 | — | [文档](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) |
| `v2_users_user_openid_info.get` | `GET /v2/users/{user_openid}/info` | 10 次/秒 | 同机器人环境的不同用户共享窗口，需好友关系 | [文档](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_info.get.html) |
