package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * QQ Bot OpenAPI 的类型化封装，包含频道消息辅助能力、频道私信和资源管理接口。
 * 请求体使用 {@link JsonNode}，便于兼容官方新增字段和扩展管理接口。
 */
public final class QQOpenApi {
    private final HttpApiClient api;
    private final String appId;

    QQOpenApi(HttpApiClient api, String appId) {
        this.api = api;
        this.appId = appId;
    }

    /** 发送原始鉴权 OpenAPI 请求；返回值为 QQ 返回的 JSON。 */
    public CompletableFuture<JsonNode> request(String method, String path, JsonNode body) {
        requireText(method, "method");
        requireText(path, "path");
        return api.requestAsync(method.toUpperCase(java.util.Locale.ROOT), path, body);
    }

    /** 获取当前机器人资料。 */
    public CompletableFuture<JsonNode> getCurrentUser() {
        return get("/users/@me");
    }

    /** 分页获取机器人可见的 Guild 列表。 */
    public CompletableFuture<JsonNode> listGuilds(String before, String after, Integer limit) {
        return get(query("/users/@me/guilds", params("before", before, "after", after, "limit", limit)));
    }

    /** 获取 Guild 详情。 */
    public CompletableFuture<JsonNode> getGuild(String guildId) {
        return get("/guilds/" + id(guildId, "guildId"));
    }

    /** 获取 Guild 下的频道列表。 */
    public CompletableFuture<JsonNode> listGuildChannels(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/channels");
    }

    /** 在 Guild 中创建频道。 */
    public CompletableFuture<JsonNode> createGuildChannel(String guildId, JsonNode channel) {
        return post("/guilds/" + id(guildId, "guildId") + "/channels", object(channel, "channel"));
    }

    /** 获取频道详情。 */
    public CompletableFuture<JsonNode> getChannel(String channelId) {
        return get("/channels/" + id(channelId, "channelId"));
    }

    /** 更新频道字段。 */
    public CompletableFuture<JsonNode> updateChannel(String channelId, JsonNode patch) {
        return patch("/channels/" + id(channelId, "channelId"), object(patch, "patch"));
    }

    /** 删除频道。 */
    public CompletableFuture<JsonNode> deleteChannel(String channelId) {
        return delete("/channels/" + id(channelId, "channelId"));
    }

    /** 分页获取 Guild 成员列表。 */
    public CompletableFuture<JsonNode> listGuildMembers(String guildId, String after, Integer limit) {
        String path = "/guilds/" + id(guildId, "guildId") + "/members";
        return get(query(path, params("after", after, "limit", limit)));
    }

    /** 获取单个 Guild 成员。 */
    public CompletableFuture<JsonNode> getGuildMember(String guildId, String userId) {
        return get(guildMemberPath(guildId, userId));
    }

    /** 移除 Guild 成员，使用默认的历史消息删除设置。 */
    public CompletableFuture<JsonNode> removeGuildMember(String guildId, String userId, boolean addBlacklist) {
        return removeGuildMember(guildId, userId, addBlacklist, 0);
    }

    /** 按黑名单和历史消息选项移除 Guild 成员。 */
    public CompletableFuture<JsonNode> removeGuildMember(String guildId, String userId,
                                                          boolean addBlacklist, int deleteHistoryMessageDays) {
        JsonNode body = JsonNodeFactory.instance.objectNode()
                .put("add_blacklist", addBlacklist)
                .put("delete_history_msg_days", deleteHistoryMessageDays);
        return request("DELETE", guildMemberPath(guildId, userId), body);
    }

    /** 更新成员禁言设置。 */
    public CompletableFuture<JsonNode> muteGuildMember(String guildId, String userId, JsonNode mute) {
        return patch(guildMemberPath(guildId, userId) + "/mute", object(mute, "mute"));
    }

    /** 更新 Guild 级禁言设置。 */
    public CompletableFuture<JsonNode> muteGuild(String guildId, JsonNode mute) {
        return patch("/guilds/" + id(guildId, "guildId") + "/mute", object(mute, "mute"));
    }

    /** 获取 Guild 角色列表。 */
    public CompletableFuture<JsonNode> listGuildRoles(String guildId) {
        return get(guildRolesPath(guildId));
    }

    /** 创建 Guild 角色。 */
    public CompletableFuture<JsonNode> createGuildRole(String guildId, JsonNode role) {
        return post(guildRolesPath(guildId), object(role, "role"));
    }

    /** 更新 Guild 角色。 */
    public CompletableFuture<JsonNode> updateGuildRole(String guildId, String roleId, JsonNode role) {
        return patch(guildRolesPath(guildId) + "/" + id(roleId, "roleId"), object(role, "role"));
    }

    /** 删除 Guild 角色。 */
    public CompletableFuture<JsonNode> deleteGuildRole(String guildId, String roleId) {
        return delete(guildRolesPath(guildId) + "/" + id(roleId, "roleId"));
    }

    /** 获取分配了指定 Guild 角色的成员。 */
    public CompletableFuture<JsonNode> listGuildRoleMembers(String guildId, String roleId,
                                                             String startIndex, Integer limit) {
        String path = guildRolesPath(guildId) + "/" + id(roleId, "roleId") + "/members";
        return get(query(path, params("start_index", startIndex, "limit", limit)));
    }

    /** 为 Guild 成员分配角色。 */
    public CompletableFuture<JsonNode> addGuildMemberRole(String guildId, String userId,
                                                           String roleId, String channelId) {
        String path = guildMemberPath(guildId, userId) + "/roles/" + id(roleId, "roleId");
        return request("PUT", path, optionalChannel(channelId));
    }

    /** 移除 Guild 成员的角色。 */
    public CompletableFuture<JsonNode> removeGuildMemberRole(String guildId, String userId,
                                                              String roleId, String channelId) {
        String path = guildMemberPath(guildId, userId) + "/roles/" + id(roleId, "roleId");
        return request("DELETE", path, optionalChannel(channelId));
    }

    /** 获取 Guild API 权限状态。 */
    public CompletableFuture<JsonNode> getGuildApiPermissions(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/api_permission");
    }

    /** 提交 Guild API 权限申请。 */
    public CompletableFuture<JsonNode> demandGuildApiPermission(String guildId, JsonNode demand) {
        return post("/guilds/" + id(guildId, "guildId") + "/api_permission/demand", object(demand, "demand"));
    }

    /** 获取成员的频道权限。 */
    public CompletableFuture<JsonNode> getMemberChannelPermissions(String channelId, String userId) {
        return get(channelPermissionPath(channelId, "members", userId));
    }

    /** 更新成员的频道权限。 */
    public CompletableFuture<JsonNode> updateMemberChannelPermissions(String channelId, String userId,
                                                                       JsonNode permissions) {
        return put(channelPermissionPath(channelId, "members", userId), object(permissions, "permissions"));
    }

    /** 获取角色的频道权限。 */
    public CompletableFuture<JsonNode> getRoleChannelPermissions(String channelId, String roleId) {
        return get(channelPermissionPath(channelId, "roles", roleId));
    }

    /** 更新角色的频道权限。 */
    public CompletableFuture<JsonNode> updateRoleChannelPermissions(String channelId, String roleId,
                                                                     JsonNode permissions) {
        return put(channelPermissionPath(channelId, "roles", roleId), object(permissions, "permissions"));
    }

    /** 发送互动回调响应。 */
    public CompletableFuture<JsonNode> respondInteraction(String interactionId, int code) {
        String path = "/interactions/" + id(interactionId, "interactionId");
        return api.requestAsync("PUT", path, JsonNodeFactory.instance.objectNode().put("code", code),
                Map.of("X-Callback-AppID", appId));
    }

    /** 获取频道消息的 Reaction 列表。 */
    public CompletableFuture<JsonNode> listReactions(String channelId, String messageId,
                                                      int emojiType, String emojiId,
                                                      String cookie, Integer limit) {
        return get(query(reactionPath(channelId, messageId, emojiType, emojiId),
                params("cookie", cookie, "limit", limit)));
    }

    /** 为频道消息添加 Reaction。 */
    public CompletableFuture<JsonNode> addReaction(String channelId, String messageId,
                                                    int emojiType, String emojiId) {
        return request("PUT", reactionPath(channelId, messageId, emojiType, emojiId), null);
    }

    /** 移除频道消息的 Reaction。 */
    public CompletableFuture<JsonNode> removeReaction(String channelId, String messageId,
                                                       int emojiType, String emojiId) {
        return delete(reactionPath(channelId, messageId, emojiType, emojiId));
    }

    /** 撤回私聊消息。 */
    public CompletableFuture<JsonNode> recallPrivateMessage(String userOpenId, String messageId) {
        return delete("/v2/users/" + id(userOpenId, "userOpenId") + "/messages/" + id(messageId, "messageId"));
    }

    /** 撤回群聊消息。 */
    public CompletableFuture<JsonNode> recallGroupMessage(String groupOpenId, String messageId) {
        return delete("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/messages/" + id(messageId, "messageId"));
    }

    /** 撤回频道消息。 */
    public CompletableFuture<JsonNode> recallChannelMessage(String channelId, String messageId, boolean hideTip) {
        String path = "/channels/" + id(channelId, "channelId") + "/messages/" + id(messageId, "messageId");
        return delete(query(path, params("hidetip", hideTip)));
    }

    /** 创建机器人与同一频道成员之间的频道私信会话。 */
    public CompletableFuture<JsonNode> createDirectMessage(String recipientId, String sourceGuildId) {
        JsonNode body = JsonNodeFactory.instance.objectNode()
                .put("recipient_id", text(recipientId, "recipientId"))
                .put("source_guild_id", text(sourceGuildId, "sourceGuildId"));
        return post("/users/@me/dms", body);
    }

    /** 撤回频道私信。 */
    public CompletableFuture<JsonNode> recallDirectMessage(String guildId, String messageId, boolean hideTip) {
        String path = "/dms/" + id(guildId, "guildId") + "/messages/" + id(messageId, "messageId");
        return delete(query(path, params("hidetip", hideTip)));
    }

    /** 发送或更新 C2C 流式消息。 */
    public CompletableFuture<JsonNode> streamPrivateMessage(String userOpenId, StreamMessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/stream_messages", payload.toJson());
    }

    /** 为私聊消息上传媒体。 */
    public CompletableFuture<JsonNode> uploadPrivateMedia(String userOpenId, RichMediaRequest media) {
        Objects.requireNonNull(media, "media");
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/files", media.toJson());
    }

    /** 为群聊消息上传媒体。 */
    public CompletableFuture<JsonNode> uploadGroupMedia(String groupOpenId, RichMediaRequest media) {
        Objects.requireNonNull(media, "media");
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/files", media.toJson());
    }

    /** 准备私聊消息分片上传。 */
    public CompletableFuture<JsonNode> preparePrivateMultipartUpload(String userOpenId, JsonNode request) {
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/upload_prepare", object(request, "request"));
    }

    /** 完成私聊消息分片上传。 */
    public CompletableFuture<JsonNode> finishPrivateMultipartUpload(String userOpenId, JsonNode request) {
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/upload_part_finish", object(request, "request"));
    }

    /** 准备群聊消息分片上传。 */
    public CompletableFuture<JsonNode> prepareGroupMultipartUpload(String groupOpenId, JsonNode request) {
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/upload_prepare", object(request, "request"));
    }

    /** 完成群聊消息分片上传。 */
    public CompletableFuture<JsonNode> finishGroupMultipartUpload(String groupOpenId, JsonNode request) {
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/upload_part_finish", object(request, "request"));
    }

    /** 将一个二进制分片上传到准备接口返回的预签名 URL。 */
    public CompletableFuture<Void> uploadPresignedMediaPart(String presignedUrl, byte[] data) {
        requireText(presignedUrl, "presignedUrl");
        return api.uploadPresignedPartAsync(URI.create(presignedUrl), data);
    }

    /** 根据请求体生成 URL Link。 */
    public CompletableFuture<JsonNode> generateUrlLink(JsonNode request) {
        return post("/v2/generate_url_link", object(request, "request"));
    }

    /** 获取机器人菜单配置。 */
    public CompletableFuture<JsonNode> getMenu() {
        return get("/v2/menu");
    }

    /** 替换机器人菜单配置。 */
    public CompletableFuture<JsonNode> updateMenu(JsonNode menu) {
        return put("/v2/menu", object(menu, "menu"));
    }

    /** 分页获取机器人面板列表。 */
    public CompletableFuture<JsonNode> listPanels(String scope, String cursor, Integer limit) {
        return get(query("/v2/panels", params("scope", scope, "cursor", cursor, "limit", limit)));
    }

    /** 创建机器人面板。 */
    public CompletableFuture<JsonNode> createPanel(JsonNode panel) {
        return post("/v2/panels", object(panel, "panel"));
    }

    /** 获取机器人面板详情。 */
    public CompletableFuture<JsonNode> getPanel(String panelId) {
        return get("/v2/panels/" + id(panelId, "panelId"));
    }

    /** 更新机器人面板。 */
    public CompletableFuture<JsonNode> updatePanel(String panelId, JsonNode panel) {
        return put("/v2/panels/" + id(panelId, "panelId"), object(panel, "panel"));
    }

    /** 删除机器人面板。 */
    public CompletableFuture<JsonNode> deletePanel(String panelId) {
        return delete("/v2/panels/" + id(panelId, "panelId"));
    }

    /** 设置机器人面板的目标配置。 */
    public CompletableFuture<JsonNode> setPanelTarget(String panelId, JsonNode target) {
        return put("/v2/panels/" + id(panelId, "panelId") + "/target", object(target, "target"));
    }

    /** 获取群组详情。 */
    public CompletableFuture<JsonNode> getGroupInfo(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/info");
    }

    /** 获取机器人在群组中的状态。 */
    public CompletableFuture<JsonNode> getGroupBotState(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/bot_state");
    }

    /** 获取待处理的加群申请列表。 */
    public CompletableFuture<JsonNode> listGroupJoinRequests(String groupOpenId) {
        return listGroupJoinRequests(groupOpenId, null, null);
    }

    /** 分页获取待处理的加群申请列表。 */
    public CompletableFuture<JsonNode> listGroupJoinRequests(String groupOpenId, String cursor, Integer limit) {
        return get(query(groupPath(groupOpenId) + "/join_request_list",
                params("cursor", cursor, "limit", limit)));
    }

    /** 分页获取群成员列表。 */
    public CompletableFuture<JsonNode> listGroupMembers(String groupOpenId, String cursor) {
        return get(query(groupPath(groupOpenId) + "/members", params("cursor", cursor)));
    }

    /** 获取指定群成员信息。 */
    public CompletableFuture<JsonNode> getGroupMember(String groupOpenId, String memberOpenId) {
        return get(groupPath(groupOpenId) + "/members/" + id(memberOpenId, "memberOpenId"));
    }

    /** 批量移除群成员，单次最多 20 个；请求体由官方字段定义。 */
    public CompletableFuture<JsonNode> batchRemoveGroupMembers(String groupOpenId, JsonNode request) {
        return post(groupPath(groupOpenId) + "/batch_remove_members", object(request, "request"));
    }

    /** 分页获取群黑名单。 */
    public CompletableFuture<JsonNode> listGroupMemberBlacklist(String groupOpenId,
                                                                 String cursor, Integer limit) {
        return get(query(groupPath(groupOpenId) + "/member_blacklist",
                params("cursor", cursor, "limit", limit)));
    }

    /** 添加或移除群黑名单成员，操作和成员列表由官方请求体定义。 */
    public CompletableFuture<JsonNode> updateGroupMemberBlacklist(String groupOpenId, JsonNode request) {
        return post(groupPath(groupOpenId) + "/member_blacklist", object(request, "request"));
    }

    /** 根据请求体处理加群申请。 */
    public CompletableFuture<JsonNode> approveGroupJoinRequest(String groupOpenId, String memberOpenId,
                                                               JsonNode approval) {
        String path = groupPath(groupOpenId) + "/approval_join_request/" + id(memberOpenId, "memberOpenId");
        return post(path, object(approval, "approval"));
    }

    /** 获取群聊禁言设置。 */
    public CompletableFuture<JsonNode> getGroupRestrictChatSetting(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/restrict_chat_setting");
    }

    /** 更新群聊禁言设置。 */
    public CompletableFuture<JsonNode> updateGroupRestrictChatSetting(String groupOpenId, JsonNode setting) {
        return post(groupPath(groupOpenId) + "/restrict_chat_setting", object(setting, "setting"));
    }

    /** 获取加群审核策略列表。 */
    public CompletableFuture<JsonNode> listGroupJoinApprovalStrategies() {
        return get("/v2/groups/join_approval_strategy");
    }

    /** 创建加群审核策略。 */
    public CompletableFuture<JsonNode> createGroupJoinApprovalStrategy(JsonNode strategy) {
        return post("/v2/groups/join_approval_strategy", object(strategy, "strategy"));
    }

    /** 更新加群审核策略。 */
    public CompletableFuture<JsonNode> updateGroupJoinApprovalStrategy(String strategyId, JsonNode strategy) {
        return patch(strategyPath(strategyId), object(strategy, "strategy"));
    }

    /** 删除加群审核策略。 */
    public CompletableFuture<JsonNode> deleteGroupJoinApprovalStrategy(String strategyId) {
        return delete(strategyPath(strategyId));
    }

    /** 执行加群审核策略。 */
    public CompletableFuture<JsonNode> executeGroupJoinApprovalStrategy(String strategyId, JsonNode request) {
        return post(strategyPath(strategyId) + "/execute", object(request, "request"));
    }

    /** 将用户加入加群审核白名单。 */
    public CompletableFuture<JsonNode> addGroupJoinApprovalWhitelistUsers(String strategyId, JsonNode request) {
        return post(strategyPath(strategyId) + "/whitelist_users", object(request, "request"));
    }

    /** 获取 Guild 消息设置。 */
    public CompletableFuture<JsonNode> getMessageSetting(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/message/setting");
    }

    /** 获取频道置顶消息列表。 */
    public CompletableFuture<JsonNode> getPins(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/pins");
    }

    /** 置顶频道消息。 */
    public CompletableFuture<JsonNode> pinMessage(String channelId, String messageId) {
        return request("PUT", pinPath(channelId, messageId), null);
    }

    /** 取消置顶频道消息。 */
    public CompletableFuture<JsonNode> unpinMessage(String channelId, String messageId) {
        return delete(pinPath(channelId, messageId));
    }

    /** 创建 Guild 公告。 */
    public CompletableFuture<JsonNode> createGuildAnnouncement(String guildId, JsonNode announcement) {
        return post("/guilds/" + id(guildId, "guildId") + "/announces", object(announcement, "announcement"));
    }

    /** 删除 Guild 公告。 */
    public CompletableFuture<JsonNode> deleteGuildAnnouncement(String guildId, String messageId) {
        return delete("/guilds/" + id(guildId, "guildId") + "/announces/" + id(messageId, "messageId"));
    }

    /** 创建频道公告。 */
    public CompletableFuture<JsonNode> createChannelAnnouncement(String channelId, JsonNode announcement) {
        return post("/channels/" + id(channelId, "channelId") + "/announces", object(announcement, "announcement"));
    }

    /** 删除频道公告。 */
    public CompletableFuture<JsonNode> deleteChannelAnnouncement(String channelId, String messageId) {
        return delete("/channels/" + id(channelId, "channelId") + "/announces/" + id(messageId, "messageId"));
    }

    /** 获取频道日程列表。 */
    public CompletableFuture<JsonNode> listSchedules(String channelId, String since) {
        return get(query(scheduleBase(channelId), params("since", since)));
    }

    /** 创建频道日程。 */
    public CompletableFuture<JsonNode> createSchedule(String channelId, JsonNode schedule) {
        return post(scheduleBase(channelId), wrapped("schedule", schedule));
    }

    /** 获取频道日程详情。 */
    public CompletableFuture<JsonNode> getSchedule(String channelId, String scheduleId) {
        return get(schedulePath(channelId, scheduleId));
    }

    /** 更新频道日程。 */
    public CompletableFuture<JsonNode> updateSchedule(String channelId, String scheduleId, JsonNode schedule) {
        return patch(schedulePath(channelId, scheduleId), wrapped("schedule", schedule));
    }

    /** 删除频道日程。 */
    public CompletableFuture<JsonNode> deleteSchedule(String channelId, String scheduleId) {
        return delete(schedulePath(channelId, scheduleId));
    }

    /** 获取频道主题列表。 */
    public CompletableFuture<JsonNode> listThreads(String channelId) {
        return get(threadBase(channelId));
    }

    /** 获取频道主题详情。 */
    public CompletableFuture<JsonNode> getThread(String channelId, String threadId) {
        return get(threadPath(channelId, threadId));
    }

    /** 创建频道主题。 */
    public CompletableFuture<JsonNode> createThread(String channelId, JsonNode thread) {
        return request("PUT", threadBase(channelId), object(thread, "thread"));
    }

    /** 删除频道主题。 */
    public CompletableFuture<JsonNode> deleteThread(String channelId, String threadId) {
        return delete(threadPath(channelId, threadId));
    }

    /** 对频道执行音频控制操作。 */
    public CompletableFuture<JsonNode> controlAudio(String channelId, JsonNode audioControl) {
        return post("/channels/" + id(channelId, "channelId") + "/audio", object(audioControl, "audioControl"));
    }

    /** 加入频道麦克风。 */
    public CompletableFuture<JsonNode> joinMicrophone(String channelId) {
        return request("PUT", "/channels/" + id(channelId, "channelId") + "/mic", null);
    }

    /** 离开频道麦克风。 */
    public CompletableFuture<JsonNode> leaveMicrophone(String channelId) {
        return delete("/channels/" + id(channelId, "channelId") + "/mic");
    }

    /** 获取频道在线人数。 */
    public CompletableFuture<JsonNode> getChannelOnlineCount(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/online_nums");
    }

    /** 获取语音频道成员列表。 */
    public CompletableFuture<JsonNode> listVoiceChannelMembers(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/voice/members");
    }

    /** 清除频道全部置顶消息。 */
    public CompletableFuture<JsonNode> cleanPins(String channelId) {
        return delete(pinPath(channelId, "all"));
    }

    /** 清除 Guild 全部公告。 */
    public CompletableFuture<JsonNode> cleanGuildAnnouncements(String guildId) {
        return deleteGuildAnnouncement(guildId, "all");
    }

    /** 清除频道全部公告。 */
    public CompletableFuture<JsonNode> cleanChannelAnnouncements(String channelId) {
        return deleteChannelAnnouncement(channelId, "all");
    }

    private CompletableFuture<JsonNode> get(String path) { return api.getAsync(path); }
    private CompletableFuture<JsonNode> post(String path, JsonNode body) { return api.requestAsync("POST", path, body); }
    private CompletableFuture<JsonNode> put(String path, JsonNode body) { return api.requestAsync("PUT", path, body); }
    private CompletableFuture<JsonNode> patch(String path, JsonNode body) { return api.requestAsync("PATCH", path, body); }
    private CompletableFuture<JsonNode> delete(String path) { return api.requestAsync("DELETE", path, null); }

    private static String guildMemberPath(String guildId, String userId) {
        return "/guilds/" + id(guildId, "guildId") + "/members/" + id(userId, "userId");
    }

    private static String guildRolesPath(String guildId) {
        return "/guilds/" + id(guildId, "guildId") + "/roles";
    }

    private static String channelPermissionPath(String channelId, String subjectType, String subjectId) {
        return "/channels/" + id(channelId, "channelId") + "/" + subjectType + "/"
                + id(subjectId, "subjectId") + "/permissions";
    }

    private static String reactionPath(String channelId, String messageId, int emojiType, String emojiId) {
        if (emojiType < 0) throw new IllegalArgumentException("emojiType must not be negative");
        return "/channels/" + id(channelId, "channelId") + "/messages/" + id(messageId, "messageId")
                + "/reactions/" + emojiType + "/" + id(emojiId, "emojiId");
    }

    private static String pinPath(String channelId, String messageId) {
        return "/channels/" + id(channelId, "channelId") + "/pins/" + id(messageId, "messageId");
    }

    private static String groupPath(String groupOpenId) {
        return "/v2/groups/" + id(groupOpenId, "groupOpenId");
    }

    private static String strategyPath(String strategyId) {
        return "/v2/groups/join_approval_strategy/" + id(strategyId, "strategyId");
    }

    private static String scheduleBase(String channelId) {
        return "/channels/" + id(channelId, "channelId") + "/schedules";
    }

    private static String schedulePath(String channelId, String scheduleId) {
        return scheduleBase(channelId) + "/" + id(scheduleId, "scheduleId");
    }

    private static String threadBase(String channelId) {
        return "/channels/" + id(channelId, "channelId") + "/threads";
    }

    private static String threadPath(String channelId, String threadId) {
        return threadBase(channelId) + "/" + id(threadId, "threadId");
    }

    private static JsonNode optionalChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) return JsonNodeFactory.instance.objectNode();
        return JsonNodeFactory.instance.objectNode().set("channel",
                JsonNodeFactory.instance.objectNode().put("id", channelId));
    }

    private static JsonNode wrapped(String field, JsonNode value) {
        return JsonNodeFactory.instance.objectNode().set(field, object(value, field));
    }

    private static JsonNode object(JsonNode node, String name) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(name + " must be a JSON object");
        return node;
    }

    private static String id(String value, String name) {
        requireText(value, name);
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(String value, String name) {
        requireText(value, name);
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static Map<String, Object> params(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] != null && !entries[i + 1].toString().isBlank()) {
                result.put(entries[i].toString(), entries[i + 1]);
            }
        }
        return result;
    }

    private static String query(String path, Map<String, Object> parameters) {
        if (parameters.isEmpty()) return path;
        StringBuilder result = new StringBuilder(path).append('?');
        parameters.forEach((key, value) -> {
            if (result.charAt(result.length() - 1) != '?') result.append('&');
            result.append(id(key, "query key")).append('=').append(id(value.toString(), "query value"));
        });
        return result.toString();
    }
}
