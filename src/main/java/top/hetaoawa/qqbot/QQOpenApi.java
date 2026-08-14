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
 * Named wrappers for QQ Bot OpenAPI endpoints other than channel message delivery and direct messages.
 * Request bodies are JSON because a number of management schemas are extended by QQ without a new endpoint.
 */
public final class QQOpenApi {
    private final HttpApiClient api;
    private final String appId;

    QQOpenApi(HttpApiClient api, String appId) {
        this.api = api;
        this.appId = appId;
    }

    public CompletableFuture<JsonNode> request(String method, String path, JsonNode body) {
        requireText(method, "method");
        requireText(path, "path");
        return api.requestAsync(method.toUpperCase(java.util.Locale.ROOT), path, body);
    }

    public CompletableFuture<JsonNode> getCurrentUser() {
        return get("/users/@me");
    }

    public CompletableFuture<JsonNode> listGuilds(String before, String after, Integer limit) {
        return get(query("/users/@me/guilds", params("before", before, "after", after, "limit", limit)));
    }

    public CompletableFuture<JsonNode> getGuild(String guildId) {
        return get("/guilds/" + id(guildId, "guildId"));
    }

    public CompletableFuture<JsonNode> listGuildChannels(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/channels");
    }

    public CompletableFuture<JsonNode> createGuildChannel(String guildId, JsonNode channel) {
        return post("/guilds/" + id(guildId, "guildId") + "/channels", object(channel, "channel"));
    }

    public CompletableFuture<JsonNode> getChannel(String channelId) {
        return get("/channels/" + id(channelId, "channelId"));
    }

    public CompletableFuture<JsonNode> updateChannel(String channelId, JsonNode patch) {
        return patch("/channels/" + id(channelId, "channelId"), object(patch, "patch"));
    }

    public CompletableFuture<JsonNode> deleteChannel(String channelId) {
        return delete("/channels/" + id(channelId, "channelId"));
    }

    public CompletableFuture<JsonNode> listGuildMembers(String guildId, String after, Integer limit) {
        String path = "/guilds/" + id(guildId, "guildId") + "/members";
        return get(query(path, params("after", after, "limit", limit)));
    }

    public CompletableFuture<JsonNode> getGuildMember(String guildId, String userId) {
        return get(guildMemberPath(guildId, userId));
    }

    public CompletableFuture<JsonNode> removeGuildMember(String guildId, String userId, boolean addBlacklist) {
        return removeGuildMember(guildId, userId, addBlacklist, 0);
    }

    public CompletableFuture<JsonNode> removeGuildMember(String guildId, String userId,
                                                          boolean addBlacklist, int deleteHistoryMessageDays) {
        JsonNode body = JsonNodeFactory.instance.objectNode()
                .put("add_blacklist", addBlacklist)
                .put("delete_history_msg_days", deleteHistoryMessageDays);
        return request("DELETE", guildMemberPath(guildId, userId), body);
    }

    public CompletableFuture<JsonNode> muteGuildMember(String guildId, String userId, JsonNode mute) {
        return patch(guildMemberPath(guildId, userId) + "/mute", object(mute, "mute"));
    }

    public CompletableFuture<JsonNode> muteGuild(String guildId, JsonNode mute) {
        return patch("/guilds/" + id(guildId, "guildId") + "/mute", object(mute, "mute"));
    }

    public CompletableFuture<JsonNode> listGuildRoles(String guildId) {
        return get(guildRolesPath(guildId));
    }

    public CompletableFuture<JsonNode> createGuildRole(String guildId, JsonNode role) {
        return post(guildRolesPath(guildId), object(role, "role"));
    }

    public CompletableFuture<JsonNode> updateGuildRole(String guildId, String roleId, JsonNode role) {
        return patch(guildRolesPath(guildId) + "/" + id(roleId, "roleId"), object(role, "role"));
    }

    public CompletableFuture<JsonNode> deleteGuildRole(String guildId, String roleId) {
        return delete(guildRolesPath(guildId) + "/" + id(roleId, "roleId"));
    }

    public CompletableFuture<JsonNode> listGuildRoleMembers(String guildId, String roleId,
                                                             String startIndex, Integer limit) {
        String path = guildRolesPath(guildId) + "/" + id(roleId, "roleId") + "/members";
        return get(query(path, params("start_index", startIndex, "limit", limit)));
    }

    public CompletableFuture<JsonNode> addGuildMemberRole(String guildId, String userId,
                                                           String roleId, String channelId) {
        String path = guildMemberPath(guildId, userId) + "/roles/" + id(roleId, "roleId");
        return request("PUT", path, optionalChannel(channelId));
    }

    public CompletableFuture<JsonNode> removeGuildMemberRole(String guildId, String userId,
                                                              String roleId, String channelId) {
        String path = guildMemberPath(guildId, userId) + "/roles/" + id(roleId, "roleId");
        return request("DELETE", path, optionalChannel(channelId));
    }

    public CompletableFuture<JsonNode> getGuildApiPermissions(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/api_permission");
    }

    public CompletableFuture<JsonNode> demandGuildApiPermission(String guildId, JsonNode demand) {
        return post("/guilds/" + id(guildId, "guildId") + "/api_permission/demand", object(demand, "demand"));
    }

    public CompletableFuture<JsonNode> getMemberChannelPermissions(String channelId, String userId) {
        return get(channelPermissionPath(channelId, "members", userId));
    }

    public CompletableFuture<JsonNode> updateMemberChannelPermissions(String channelId, String userId,
                                                                       JsonNode permissions) {
        return put(channelPermissionPath(channelId, "members", userId), object(permissions, "permissions"));
    }

    public CompletableFuture<JsonNode> getRoleChannelPermissions(String channelId, String roleId) {
        return get(channelPermissionPath(channelId, "roles", roleId));
    }

    public CompletableFuture<JsonNode> updateRoleChannelPermissions(String channelId, String roleId,
                                                                     JsonNode permissions) {
        return put(channelPermissionPath(channelId, "roles", roleId), object(permissions, "permissions"));
    }

    public CompletableFuture<JsonNode> respondInteraction(String interactionId, int code) {
        String path = "/interactions/" + id(interactionId, "interactionId");
        return api.requestAsync("PUT", path, JsonNodeFactory.instance.objectNode().put("code", code),
                Map.of("X-Callback-AppID", appId));
    }

    public CompletableFuture<JsonNode> listReactions(String channelId, String messageId,
                                                      int emojiType, String emojiId,
                                                      String cookie, Integer limit) {
        return get(query(reactionPath(channelId, messageId, emojiType, emojiId),
                params("cookie", cookie, "limit", limit)));
    }

    public CompletableFuture<JsonNode> addReaction(String channelId, String messageId,
                                                    int emojiType, String emojiId) {
        return request("PUT", reactionPath(channelId, messageId, emojiType, emojiId), null);
    }

    public CompletableFuture<JsonNode> removeReaction(String channelId, String messageId,
                                                       int emojiType, String emojiId) {
        return delete(reactionPath(channelId, messageId, emojiType, emojiId));
    }

    public CompletableFuture<JsonNode> recallPrivateMessage(String userOpenId, String messageId) {
        return delete("/v2/users/" + id(userOpenId, "userOpenId") + "/messages/" + id(messageId, "messageId"));
    }

    public CompletableFuture<JsonNode> recallGroupMessage(String groupOpenId, String messageId) {
        return delete("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/messages/" + id(messageId, "messageId"));
    }

    public CompletableFuture<JsonNode> recallChannelMessage(String channelId, String messageId, boolean hideTip) {
        String path = "/channels/" + id(channelId, "channelId") + "/messages/" + id(messageId, "messageId");
        return delete(query(path, params("hidetip", hideTip)));
    }

    public CompletableFuture<JsonNode> streamPrivateMessage(String userOpenId, StreamMessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/stream_messages", payload.toJson());
    }

    public CompletableFuture<JsonNode> uploadPrivateMedia(String userOpenId, RichMediaRequest media) {
        Objects.requireNonNull(media, "media");
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/files", media.toJson());
    }

    public CompletableFuture<JsonNode> uploadGroupMedia(String groupOpenId, RichMediaRequest media) {
        Objects.requireNonNull(media, "media");
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/files", media.toJson());
    }

    public CompletableFuture<JsonNode> preparePrivateMultipartUpload(String userOpenId, JsonNode request) {
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/upload_prepare", object(request, "request"));
    }

    public CompletableFuture<JsonNode> finishPrivateMultipartUpload(String userOpenId, JsonNode request) {
        return post("/v2/users/" + id(userOpenId, "userOpenId") + "/upload_part_finish", object(request, "request"));
    }

    public CompletableFuture<JsonNode> prepareGroupMultipartUpload(String groupOpenId, JsonNode request) {
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/upload_prepare", object(request, "request"));
    }

    public CompletableFuture<JsonNode> finishGroupMultipartUpload(String groupOpenId, JsonNode request) {
        return post("/v2/groups/" + id(groupOpenId, "groupOpenId") + "/upload_part_finish", object(request, "request"));
    }

    /** Uploads one binary part to the presigned URL returned by an upload-prepare endpoint. */
    public CompletableFuture<Void> uploadPresignedMediaPart(String presignedUrl, byte[] data) {
        requireText(presignedUrl, "presignedUrl");
        return api.uploadPresignedPartAsync(URI.create(presignedUrl), data);
    }

    public CompletableFuture<JsonNode> generateUrlLink(JsonNode request) {
        return post("/v2/generate_url_link", object(request, "request"));
    }

    public CompletableFuture<JsonNode> getMenu() {
        return get("/v2/menu");
    }

    public CompletableFuture<JsonNode> updateMenu(JsonNode menu) {
        return put("/v2/menu", object(menu, "menu"));
    }

    public CompletableFuture<JsonNode> listPanels(String scope, String cursor, Integer limit) {
        return get(query("/v2/panels", params("scope", scope, "cursor", cursor, "limit", limit)));
    }

    public CompletableFuture<JsonNode> createPanel(JsonNode panel) {
        return post("/v2/panels", object(panel, "panel"));
    }

    public CompletableFuture<JsonNode> getPanel(String panelId) {
        return get("/v2/panels/" + id(panelId, "panelId"));
    }

    public CompletableFuture<JsonNode> updatePanel(String panelId, JsonNode panel) {
        return put("/v2/panels/" + id(panelId, "panelId"), object(panel, "panel"));
    }

    public CompletableFuture<JsonNode> deletePanel(String panelId) {
        return delete("/v2/panels/" + id(panelId, "panelId"));
    }

    public CompletableFuture<JsonNode> setPanelTarget(String panelId, JsonNode target) {
        return put("/v2/panels/" + id(panelId, "panelId") + "/target", object(target, "target"));
    }

    public CompletableFuture<JsonNode> getGroupInfo(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/info");
    }

    public CompletableFuture<JsonNode> getGroupBotState(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/bot_state");
    }

    public CompletableFuture<JsonNode> listGroupJoinRequests(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/join_request_list");
    }

    public CompletableFuture<JsonNode> approveGroupJoinRequest(String groupOpenId, String memberOpenId,
                                                               JsonNode approval) {
        String path = groupPath(groupOpenId) + "/approval_join_request/" + id(memberOpenId, "memberOpenId");
        return post(path, object(approval, "approval"));
    }

    public CompletableFuture<JsonNode> getGroupRestrictChatSetting(String groupOpenId) {
        return get(groupPath(groupOpenId) + "/restrict_chat_setting");
    }

    public CompletableFuture<JsonNode> updateGroupRestrictChatSetting(String groupOpenId, JsonNode setting) {
        return post(groupPath(groupOpenId) + "/restrict_chat_setting", object(setting, "setting"));
    }

    public CompletableFuture<JsonNode> listGroupJoinApprovalStrategies() {
        return get("/v2/groups/join_approval_strategy");
    }

    public CompletableFuture<JsonNode> createGroupJoinApprovalStrategy(JsonNode strategy) {
        return post("/v2/groups/join_approval_strategy", object(strategy, "strategy"));
    }

    public CompletableFuture<JsonNode> updateGroupJoinApprovalStrategy(String strategyId, JsonNode strategy) {
        return patch(strategyPath(strategyId), object(strategy, "strategy"));
    }

    public CompletableFuture<JsonNode> deleteGroupJoinApprovalStrategy(String strategyId) {
        return delete(strategyPath(strategyId));
    }

    public CompletableFuture<JsonNode> executeGroupJoinApprovalStrategy(String strategyId, JsonNode request) {
        return post(strategyPath(strategyId) + "/execute", object(request, "request"));
    }

    public CompletableFuture<JsonNode> addGroupJoinApprovalWhitelistUsers(String strategyId, JsonNode request) {
        return post(strategyPath(strategyId) + "/whitelist_users", object(request, "request"));
    }

    public CompletableFuture<JsonNode> getMessageSetting(String guildId) {
        return get("/guilds/" + id(guildId, "guildId") + "/message/setting");
    }

    public CompletableFuture<JsonNode> getPins(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/pins");
    }

    public CompletableFuture<JsonNode> pinMessage(String channelId, String messageId) {
        return request("PUT", pinPath(channelId, messageId), null);
    }

    public CompletableFuture<JsonNode> unpinMessage(String channelId, String messageId) {
        return delete(pinPath(channelId, messageId));
    }

    public CompletableFuture<JsonNode> createGuildAnnouncement(String guildId, JsonNode announcement) {
        return post("/guilds/" + id(guildId, "guildId") + "/announces", object(announcement, "announcement"));
    }

    public CompletableFuture<JsonNode> deleteGuildAnnouncement(String guildId, String messageId) {
        return delete("/guilds/" + id(guildId, "guildId") + "/announces/" + id(messageId, "messageId"));
    }

    public CompletableFuture<JsonNode> createChannelAnnouncement(String channelId, JsonNode announcement) {
        return post("/channels/" + id(channelId, "channelId") + "/announces", object(announcement, "announcement"));
    }

    public CompletableFuture<JsonNode> deleteChannelAnnouncement(String channelId, String messageId) {
        return delete("/channels/" + id(channelId, "channelId") + "/announces/" + id(messageId, "messageId"));
    }

    public CompletableFuture<JsonNode> listSchedules(String channelId, String since) {
        return get(query(scheduleBase(channelId), params("since", since)));
    }

    public CompletableFuture<JsonNode> createSchedule(String channelId, JsonNode schedule) {
        return post(scheduleBase(channelId), wrapped("schedule", schedule));
    }

    public CompletableFuture<JsonNode> getSchedule(String channelId, String scheduleId) {
        return get(schedulePath(channelId, scheduleId));
    }

    public CompletableFuture<JsonNode> updateSchedule(String channelId, String scheduleId, JsonNode schedule) {
        return patch(schedulePath(channelId, scheduleId), wrapped("schedule", schedule));
    }

    public CompletableFuture<JsonNode> deleteSchedule(String channelId, String scheduleId) {
        return delete(schedulePath(channelId, scheduleId));
    }

    public CompletableFuture<JsonNode> listThreads(String channelId) {
        return get(threadBase(channelId));
    }

    public CompletableFuture<JsonNode> getThread(String channelId, String threadId) {
        return get(threadPath(channelId, threadId));
    }

    public CompletableFuture<JsonNode> createThread(String channelId, JsonNode thread) {
        return request("PUT", threadBase(channelId), object(thread, "thread"));
    }

    public CompletableFuture<JsonNode> deleteThread(String channelId, String threadId) {
        return delete(threadPath(channelId, threadId));
    }

    public CompletableFuture<JsonNode> controlAudio(String channelId, JsonNode audioControl) {
        return post("/channels/" + id(channelId, "channelId") + "/audio", object(audioControl, "audioControl"));
    }

    public CompletableFuture<JsonNode> joinMicrophone(String channelId) {
        return request("PUT", "/channels/" + id(channelId, "channelId") + "/mic", null);
    }

    public CompletableFuture<JsonNode> leaveMicrophone(String channelId) {
        return delete("/channels/" + id(channelId, "channelId") + "/mic");
    }

    public CompletableFuture<JsonNode> getChannelOnlineCount(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/online_nums");
    }

    public CompletableFuture<JsonNode> listVoiceChannelMembers(String channelId) {
        return get("/channels/" + id(channelId, "channelId") + "/voice/members");
    }

    public CompletableFuture<JsonNode> cleanPins(String channelId) {
        return delete(pinPath(channelId, "all"));
    }

    public CompletableFuture<JsonNode> cleanGuildAnnouncements(String guildId) {
        return deleteGuildAnnouncement(guildId, "all");
    }

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
