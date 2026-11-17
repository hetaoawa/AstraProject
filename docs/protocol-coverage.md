# QQ 协议接口覆盖

接口目录核对日期为 2026-10-09，事件模型和常用类型化请求核对日期为 2026-10-12。实现参考 QQ 官方 API-v2 文档，不依赖官方 SDK。

2026-11-16 对照 [官方变更记录](https://bot.q.qq.com/wiki/develop/api-v2/changelog.html) 补充图文卡片（`msg_type=8`）、群内 @全体成员与素材管理。已有的 `picture` 卡片类型、Markdown 图片转存校验、群管理、菜单和指令面板接口、统一域名及分享链接字段已检查。三种消息事件的附件、作者、场景、引用和递归元素均有本地样本检查。

2026-11-17 根据官方 20261116 更新新增用户资料查询（10 QPS），移除批量踢人请求中已取消的拉黑参数，组合方法改用两个独立接口。补充群机器人和成员增减事件的统一标识，验证当前单聊、群消息和群 mentions 的字段，以及缺少消息元素作者时的读取。接口索引中的流式发送、分片完成和频道详情仅调整顺序，现有方法和路径保持一致。

目录包含 98 个操作 ID。频道全员禁言和批量禁言共用同一路径，按请求形式分别列出，因此对应 97 个不同的 HTTP 方法和路径模板。

## 调用入口

`PlatformApi.call(operation, path, query, body)` 统一处理相对路径、参数编码、QQBot 鉴权、JSON 请求、错误码和追踪信息。`QqEndpoints` 提供操作 ID 常量，响应保留完整字段，数组响应包装在 `items` 中。

请求和响应 Map 可包含平台扩展字段。常用请求也有 `MessagePayload`、`Moderation`、`RequestPayload` 类型，详见 [消息与平台请求](messages.md)。事件字段见 [事件与报文归档](events.md)。

`POST /app/getAppAccessToken` 由 `TokenManager` 管理，也可通过 `raw` 调用。媒体预签名 PUT 分片和 multipart 使用专门上传入口。限速和业务额度见 [接口限速与配额](rate-limits.md)。

## 已有验证范围

`ProtocolContractTest` 遍历全部目录，检查 HTTP 方法、路径参数编码、鉴权和响应解析。有可提取官方示例的项目还检查样本正文。空请求体样本只验证路由和传输格式，所需业务参数仍需按接口文档填写。签名向量、频道撤回和未知事件另有验证。

表中所有接口都尚未使用真实机器人凭据和权限进行平台业务验证。无权限、内邀或能力未开放时，框架保留平台错误。已有本地检查见 [验收记录](validation.md)，上线仍需按账号权限逐项确认。

## 接口清单

| 操作 ID | 方法和路径 | 请求／响应字段来源 | 验证样本 | 平台验证 |
| --- | --- | --- | --- | --- |
| `v2_users_user_openid_info.get` | `GET /v2/users/{user_openid}/info` | [用户基本信息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_info.get.html) | 路由、鉴权、响应字段、缓存及 10 QPS | 未验证 |
| `v2_media_images_prepare.post` | `POST /v2/media-images/prepare` | [素材管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) | 请求与响应字段 | 未验证 |
| `v2_media_images_complete.post` | `POST /v2/media-images/complete` | [素材管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) | 请求与响应字段 | 未验证 |
| `v2_media_images.get` | `GET /v2/media-images` | [素材管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) | 列表与游标响应 | 未验证 |
| `v2_media_images_image_id_delete.delete` | `DELETE /v2/media-images/{image_id}/delete` | [素材管理](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/material-management.html) | 路由与响应结构 | 未验证 |
| `channels_channel_id.delete` | `DELETE /channels/{channel_id}` | [删除子频道](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.delete.html) | 路由与响应结构 | 未验证 |
| `channels_channel_id.get` | `GET /channels/{channel_id}` | [获取子频道详情](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.get.html) | 路由与响应结构 | 未验证 |
| `channels_channel_id.patch` | `PATCH /channels/{channel_id}` | [修改子频道](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/channels_channel_id.patch.html) | 官方样本与路由 | 未验证 |
| `gateway.get` | `GET /gateway` | [获取通用 WSS 接入点](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/gateway.get.html) | 路由与响应结构 | 未验证 |
| `guilds_guild_id_channels.get` | `GET /guilds/{guild_id}/channels` | [获取子频道列表](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id_channels.get.html) | 路由与响应结构 | 未验证 |
| `guilds_guild_id.get` | `GET /guilds/{guild_id}` | [获取频道详情](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id.get.html) | 路由与响应结构 | 未验证 |
| `users_me.get` | `GET /users/@me` | [获取机器人详情](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/users_me.get.html) | 路由与响应结构 | 未验证 |
| `interactions_interaction_id.put` | `PUT /interactions/{interaction_id}` | [互动事件响应](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/interactions_interaction_id.put.html) | 官方样本与路由 | 未验证 |
| `users_me_guilds.get` | `GET /users/@me/guilds` | [获取机器人频道列表](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/users_me_guilds.get.html) | 路由与响应结构 | 未验证 |
| `v2_generate_url_link.post` | `POST /v2/generate_url_link` | [生成分享链接](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_generate_url_link.post.html) | 官方样本与路由 | 未验证 |
| `guilds_guild_id_channels.post` | `POST /guilds/{guild_id}/channels` | [创建子频道](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/guilds_guild_id_channels.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_id_upload_part_finish.post` | `POST /v2/groups/{group_id}/upload_part_finish` | [群聊分片上传完成](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_part_finish.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_id_upload_prepare.post` | `POST /v2/groups/{group_id}/upload_prepare` | [群聊富媒体预上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_id_upload_prepare.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_approval_join_request_member_openid.post` | `POST /v2/groups/{group_openid}/approval_join_request/{member_openid}` | [入群申请审批](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_approval_join_request_member_openid.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_batch_remove_members.post` | `POST /v2/groups/{group_openid}/batch_remove_members` | [群成员批量移除](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_batch_remove_members.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_bot_state.get` | `GET /v2/groups/{group_openid}/bot_state` | [获取机器人群内状态](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_bot_state.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_files.post` | `POST /v2/groups/{group_openid}/files` | [群聊富媒体上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_files.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_info.get` | `GET /v2/groups/{group_openid}/info` | [获取群基本信息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_info.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_join_request_list.get` | `GET /v2/groups/{group_openid}/join_request_list` | [入群申请列表拉取](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_join_request_list.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_member_blacklist.post` | `POST /v2/groups/{group_openid}/member_blacklist` | [群黑名单操作](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_member_blacklist.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_member_blacklist.get` | `GET /v2/groups/{group_openid}/member_blacklist` | [群黑名单查询](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_member_blacklist.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_members.get` | `GET /v2/groups/{group_openid}/members` | [获取群成员列表](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_members.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_messages.post` | `POST /v2/groups/{group_openid}/messages` | [发送群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_members_member_openid.get` | `GET /v2/groups/{group_openid}/members/{member_openid}` | [获取群成员信息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_members_member_openid.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_messages_message_id.delete` | `DELETE /v2/groups/{group_openid}/messages/{message_id}` | [撤回群聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_messages_message_id.delete.html) | 路由与响应结构 | 未验证 |
| `v2_groups_group_openid_restrict_chat_setting.post` | `POST /v2/groups/{group_openid}/restrict_chat_setting` | [设置群成员禁言](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_restrict_chat_setting.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_join_approval_strategy.get` | `GET /v2/groups/join_approval_strategy` | [查询入群自动审批策略列表](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_join_approval_strategy.post` | `POST /v2/groups/join_approval_strategy` | [创建入群自动审批策略](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_group_openid_restrict_chat_setting.get` | `GET /v2/groups/{group_openid}/restrict_chat_setting` | [查询群禁言状态](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_group_openid_restrict_chat_setting.get.html) | 路由与响应结构 | 未验证 |
| `v2_groups_join_approval_strategy_strategy_id.delete` | `DELETE /v2/groups/join_approval_strategy/{strategy_id}` | [删除入群自动审批策略](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id.delete.html) | 路由与响应结构 | 未验证 |
| `v2_groups_join_approval_strategy_strategy_id.patch` | `PATCH /v2/groups/join_approval_strategy/{strategy_id}` | [修改入群自动审批策略](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id.patch.html) | 官方样本与路由 | 未验证 |
| `v2_groups_join_approval_strategy_strategy_id_execute.post` | `POST /v2/groups/join_approval_strategy/{strategy_id}/execute` | [执行入群自动审批策略](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id_execute.post.html) | 官方样本与路由 | 未验证 |
| `v2_groups_join_approval_strategy_strategy_id_whitelist_users.post` | `POST /v2/groups/join_approval_strategy/{strategy_id}/whitelist_users` | [修改入群自动审批策略的白名单号码](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_groups_join_approval_strategy_strategy_id_whitelist_users.post.html) | 官方样本与路由 | 未验证 |
| `v2_menu.get` | `GET /v2/menu` | [查询全局自定义菜单](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_menu.get.html) | 路由与响应结构 | 未验证 |
| `v2_menu.put` | `PUT /v2/menu` | [修改全局自定义菜单](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_menu.put.html) | 官方样本与路由 | 未验证 |
| `v2_panels.get` | `GET /v2/panels` | [查询指令面板列表](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels.get.html) | 路由与响应结构 | 未验证 |
| `v2_panels_panel_id.delete` | `DELETE /v2/panels/{panel_id}` | [删除指令面板](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.delete.html) | 路由与响应结构 | 未验证 |
| `v2_panels_panel_id.get` | `GET /v2/panels/{panel_id}` | [查询指令面板详情](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.get.html) | 路由与响应结构 | 未验证 |
| `v2_panels.post` | `POST /v2/panels` | [创建指令面板](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels.post.html) | 官方样本与路由 | 未验证 |
| `v2_users_user_id_upload_prepare.post` | `POST /v2/users/{user_id}/upload_prepare` | [单聊富媒体预上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_prepare.post.html) | 官方样本与路由 | 未验证 |
| `v2_panels_panel_id.put` | `PUT /v2/panels/{panel_id}` | [修改指令面板](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id.put.html) | 官方样本与路由 | 未验证 |
| `v2_users_user_id_upload_part_finish.post` | `POST /v2/users/{user_id}/upload_part_finish` | [单聊分片上传完成](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_id_upload_part_finish.post.html) | 官方样本与路由 | 未验证 |
| `v2_users_user_openid_files.post` | `POST /v2/users/{user_openid}/files` | [单聊富媒体上传](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_files.post.html) | 官方样本与路由 | 未验证 |
| `v2_panels_panel_id_target.put` | `PUT /v2/panels/{panel_id}/target` | [修改指令面板关联对象](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_panels_panel_id_target.put.html) | 官方样本与路由 | 未验证 |
| `v2_users_user_openid_messages_message_id.delete` | `DELETE /v2/users/{user_openid}/messages/{message_id}` | [撤回单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages_message_id.delete.html) | 路由与响应结构 | 未验证 |
| `v2_users_user_openid_messages.post` | `POST /v2/users/{user_openid}/messages` | [发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html) | 官方样本与路由 | 未验证 |
| `v2_users_user_openid_stream_messages.post` | `POST /v2/users/{user_openid}/stream_messages` | [流式发送单聊消息](https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_stream_messages.post.html) | 官方样本与路由 | 未验证 |
| `get_guild_api_permission` | `GET /guilds/{guild_id}/api_permission` | [获取机器人在频道可用权限列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/api_permissions/get_guild_api_permission.html) | 路由与响应结构 | 未验证 |
| `delete_guild_announces` | `DELETE /guilds/{guild_id}/announces/{message_id}` | [删除频道公告](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/announces/delete_guild_announces.html) | 路由与响应结构 | 未验证 |
| `post_api_permission_demand` | `POST /guilds/{guild_id}/api_permission/demand` | [发送机器人在频道接口权限的授权链接](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/api_permissions/post_api_permission_demand.html) | 官方样本与路由 | 未验证 |
| `audio_control` | `POST /channels/{channel_id}/audio` | [音频控制](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/audio_control.html) | 官方样本与路由 | 未验证 |
| `delete_mic` | `DELETE /channels/{channel_id}/mic` | [机器人下麦](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/delete_mic.html) | 路由与响应结构 | 未验证 |
| `delete_thread` | `DELETE /channels/{channel_id}/threads/{thread_id}` | [删除帖子](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/delete_thread.html) | 路由与响应结构 | 未验证 |
| `post_guild_announces` | `POST /guilds/{guild_id}/announces` | [创建频道公告](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/announces/post_guild_announces.html) | 官方样本与路由 | 未验证 |
| `put_mic` | `PUT /channels/{channel_id}/mic` | [机器人上麦](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/audio/put_mic.html) | 官方样本与路由 | 未验证 |
| `get_threads_list` | `GET /channels/{channel_id}/threads` | [获取帖子列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/get_threads_list.html) | 路由与响应结构 | 未验证 |
| `get_thread` | `GET /channels/{channel_id}/threads/{thread_id}` | [获取帖子详情](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/get_thread.html) | 路由与响应结构 | 未验证 |
| `put_thread` | `PUT /channels/{channel_id}/threads` | [发表帖子](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/forum/put_thread.html) | 官方样本与路由 | 未验证 |
| `get_pins_message` | `GET /channels/{channel_id}/pins` | [获取精华消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/get_pins_message.html) | 路由与响应结构 | 未验证 |
| `delete_pins_message` | `DELETE /channels/{channel_id}/pins/{message_id}` | [删除精华消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/delete_pins_message.html) | 路由与响应结构 | 未验证 |
| `delete_schedule` | `DELETE /channels/{channel_id}/schedules/{schedule_id}` | [删除日程](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/delete_schedule.html) | 路由与响应结构 | 未验证 |
| `put_pins_message` | `PUT /channels/{channel_id}/pins/{message_id}` | [添加精华消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/pins/put_pins_message.html) | 路由与响应结构 | 未验证 |
| `get_schedule` | `GET /channels/{channel_id}/schedules/{schedule_id}` | [获取日程详情](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/get_schedule.html) | 路由与响应结构 | 未验证 |
| `get_schedules` | `GET /channels/{channel_id}/schedules` | [获取频道日程列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/get_schedules.html) | 路由与响应结构 | 未验证 |
| `patch_schedule` | `PATCH /channels/{channel_id}/schedules/{schedule_id}` | [修改日程](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/patch_schedule.html) | 官方样本与路由 | 未验证 |
| `post_schedule` | `POST /channels/{channel_id}/schedules` | [创建日程](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/content/schedule/post_schedule.html) | 官方样本与路由 | 未验证 |
| `dms.delete.dms_guild_id_messages_message_id` | `DELETE /dms/{guild_id}/messages/{message_id}` | [频道私信](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html) | 路由与响应结构 | 未验证 |
| `dms.post.dms_guild_id_messages` | `POST /dms/{guild_id}/messages` | [频道私信](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/dms.html) | 官方样本与路由 | 未验证 |
| `recall` | `DELETE /channels/{channel_id}/messages/{message_id}` | [撤回子频道消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/recall.html) | 路由与响应结构 | 未验证 |
| `send` | `POST /channels/{channel_id}/messages` | [发送子频道消息](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/message/send.html) | 官方样本与路由 | 未验证 |
| `get_channel_roles_permissions` | `GET /channels/{channel_id}/roles/{role_id}/permissions` | [获取子频道身份组权限](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/get_channel_roles_permissions.html) | 路由与响应结构 | 未验证 |
| `get_channel_permissions` | `GET /channels/{channel_id}/members/{user_id}/permissions` | [获取子频道用户权限](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/get_channel_permissions.html) | 路由与响应结构 | 未验证 |
| `put_channel_roles_permissions` | `PUT /channels/{channel_id}/roles/{role_id}/permissions` | [修改子频道身份组权限](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/put_channel_roles_permissions.html) | 路由与响应结构 | 未验证 |
| `put_channel_permissions` | `PUT /channels/{channel_id}/members/{user_id}/permissions` | [修改子频道用户权限](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/channel_permissions/put_channel_permissions.html) | 路由与响应结构 | 未验证 |
| `get_guild_roles` | `GET /guilds/{guild_id}/roles` | [获取频道身份组列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/get_guild_roles.html) | 路由与响应结构 | 未验证 |
| `delete_guild_member_role` | `DELETE /guilds/{guild_id}/members/{user_id}/roles/{role_id}` | [删除频道身份组成员](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/delete_guild_member_role.html) | 路由与响应结构 | 未验证 |
| `post_guild_role` | `POST /guilds/{guild_id}/roles` | [创建频道身份组](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/post_guild_role.html) | 官方样本与路由 | 未验证 |
| `delete_guild_role` | `DELETE /guilds/{guild_id}/roles/{role_id}` | [删除频道身份组](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/delete_guild_role.html) | 路由与响应结构 | 未验证 |
| `patch_guild_role` | `PATCH /guilds/{guild_id}/roles/{role_id}` | [修改频道身份组](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/patch_guild_role.html) | 官方样本与路由 | 未验证 |
| `put_guild_member_role` | `PUT /guilds/{guild_id}/members/{user_id}/roles/{role_id}` | [创建频道身份组成员](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role-group/put_guild_member_role.html) | 路由与响应结构 | 未验证 |
| `get_online_nums` | `GET /channels/{channel_id}/online_nums` | [获取子频道在线成员数](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/get_online_nums.html) | 路由与响应结构 | 未验证 |
| `get_member` | `GET /guilds/{guild_id}/members/{user_id}` | [获取频道成员详情](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_member.html) | 路由与响应结构 | 未验证 |
| `get_members` | `GET /guilds/{guild_id}/members` | [获取频道成员列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_members.html) | 路由与响应结构 | 未验证 |
| `delete_member` | `DELETE /guilds/{guild_id}/members/{user_id}` | [删除频道成员](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/delete_member.html) | 路由与响应结构 | 未验证 |
| `get_role_members` | `GET /guilds/{guild_id}/roles/{role_id}/members` | [获取频道身份组成员列表](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/role/member/get_role_members.html) | 路由与响应结构 | 未验证 |
| `patch_guild_member_mute` | `PATCH /guilds/{guild_id}/members/{user_id}/mute` | [频道指定成员禁言](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_member_mute.html) | 路由与响应结构 | 未验证 |
| `patch_guild_mute_multi_member` | `PATCH /guilds/{guild_id}/mute` | [频道批量成员禁言](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_mute_multi_member.html) | 官方样本与路由 | 未验证 |
| `message_setting` | `GET /guilds/{guild_id}/message/setting` | [获取频道消息频率的设置详情](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/setting/message_setting.html) | 路由与响应结构 | 未验证 |
| `patch_guild_mute` | `PATCH /guilds/{guild_id}/mute` | [频道全员禁言](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/channel/speak/patch_guild_mute.html) | 路由与响应结构 | 未验证 |
| `gateway_bot.get` | `GET /gateway/bot` | [获取带分片 WSS 接入点](https://bot.q.qq.com/wiki/develop/api-v2/openapi/wss/shard_url_get.html) | 路由与响应结构 | 未验证 |
| `channel_reactions.put` | `PUT /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | [频道表情表态](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) | 官方样本与路由 | 未验证 |
| `channel_reactions.delete` | `DELETE /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | [频道表情表态](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) | 路由与响应结构 | 未验证 |
| `channel_reactions.get` | `GET /channels/{channel_id}/messages/{message_id}/reactions/{type}/{id}` | [频道表情表态](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/trans/emoji.html) | 路由与响应结构 | 未验证 |


## 其他协议来源

[事件订阅、WebSocket 和挑战验证](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/event-emit.html)、[Webhook 签名](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html)。平台新增接口可先通过 `raw` 调用，再补充操作目录和验证样本。
