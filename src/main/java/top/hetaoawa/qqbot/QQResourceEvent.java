package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Guild、频道、成员、角色、论坛和音频资源变更的标准化事件。
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param resourceId 资源 ID
 * @param guildId Guild ID
 * @param channelId 频道 ID
 * @param userId 用户 ID
 * @param data 标准化数据
 * @param raw 原始 JSON
 */
public record QQResourceEvent(
        String eventId,
        String eventType,
        String resourceId,
        String guildId,
        String channelId,
        String userId,
        JsonNode data,
        JsonNode raw
) {
    private static final Set<String> TYPES = Set.of(
            "GUILD_CREATE", "GUILD_UPDATE", "GUILD_DELETE",
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE",
            "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE", "GUILD_MEMBER_REMOVE",
            "FORUM_THREAD_CREATE", "FORUM_THREAD_UPDATE", "FORUM_THREAD_DELETE",
            "FORUM_POST_CREATE", "FORUM_POST_DELETE", "FORUM_REPLY_CREATE", "FORUM_REPLY_DELETE",
            "AUDIO_START", "AUDIO_FINISH", "AUDIO_ON_MIC", "AUDIO_OFF_MIC"
    );

    static boolean supports(String type) { return TYPES.contains(type); }

    static QQResourceEvent from(QQEvent event) {
        JsonNode data = event.data();
        return new QQResourceEvent(event.id(), event.type(), text(data, "id"), text(data, "guild_id"),
                text(data, "channel_id"), first(text(data, "user_id"), text(data.path("user"), "id")),
                data, event.raw());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String first(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
