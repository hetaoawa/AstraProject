package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 标准化互动事件，并提供互动响应方法。
 * @param bot 所属 Bot
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param interactionId 互动 ID
 * @param applicationId 应用 ID
 * @param userOpenId 用户 OpenID
 * @param groupOpenId 群 OpenID
 * @param chatType 会话类型
 * @param scene 互动场景
 * @param data 标准化数据
 * @param raw 原始 JSON
 */
public record QQInteractionEvent(
        QQBot bot,
        String eventId,
        String eventType,
        String interactionId,
        String applicationId,
        String userOpenId,
        String groupOpenId,
        Integer chatType,
        String scene,
        JsonNode data,
        JsonNode raw
) {
    private static final Set<String> TYPES = Set.of(
            "INTERACTION_CREATE", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE"
    );

    static boolean supports(String type) { return TYPES.contains(type); }

    static QQInteractionEvent from(QQBot bot, QQEvent event) {
        JsonNode data = event.data();
        JsonNode author = data.path("author");
        return new QQInteractionEvent(
                bot, event.id(), event.type(), text(data, "id"), text(data, "application_id"),
                first(text(data, "user_openid"), text(author, "user_openid")),
                text(data, "group_openid"), integer(data, "chat_type"), text(data, "scene"), data, event.raw());
    }

    /** 使用指定回调码发送互动响应。 */
    public CompletableFuture<JsonNode> respond(int code) {
        if (interactionId == null || interactionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("event has no interaction id"));
        }
        return bot.api().respondInteraction(interactionId, code);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private static String first(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
