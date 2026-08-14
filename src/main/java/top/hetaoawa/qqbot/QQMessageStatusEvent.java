package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** 标准化的消息投递或审核状态事件。
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param messageId 消息 ID
 * @param userOpenId 用户 OpenID
 * @param groupOpenId 群 OpenID
 * @param status 状态文本
 * @param statusCode 状态码
 * @param data 标准化数据
 * @param raw 原始 JSON
 */
public record QQMessageStatusEvent(
        String eventId,
        String eventType,
        String messageId,
        String userOpenId,
        String groupOpenId,
        String status,
        Integer statusCode,
        JsonNode data,
        JsonNode raw
) {
    private static final Set<String> TYPES = Set.of("SUBSCRIBE_MESSAGE_STATUS", "MESSAGE_AUDIT_PASS", "MESSAGE_AUDIT_REJECT");

    static boolean supports(String type) { return TYPES.contains(type); }

    static QQMessageStatusEvent from(QQEvent event) {
        JsonNode data = event.data();
        return new QQMessageStatusEvent(event.id(), event.type(), text(data, "message_id"),
                text(data, "user_openid"), text(data, "group_openid"), text(data, "status"),
                integer(data, "status_code"), data, event.raw());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }
}
