package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

/** Normalized friend, group membership and robot relationship event. */
/** Normalized friend, group-member, or relationship event. */
public record QQRelationshipEvent(
        String eventId,
        String eventType,
        String userOpenId,
        String groupOpenId,
        String memberOpenId,
        String operatorOpenId,
        Instant timestamp,
        JsonNode data,
        JsonNode raw
) {
    private static final Set<String> TYPES = Set.of(
            "FRIEND_ADD", "FRIEND_DEL", "C2C_MSG_RECEIVE", "C2C_MSG_REJECT",
            "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT",
            "GROUP_JOIN_REQUEST", "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE"
    );

    static boolean supports(String type) { return TYPES.contains(type); }

    static QQRelationshipEvent from(QQEvent event) {
        JsonNode data = event.data();
        return new QQRelationshipEvent(event.id(), event.type(),
                text(data, "user_openid"), text(data, "group_openid"), text(data, "member_openid"),
                first(text(data, "operator_openid"), text(data, "op_member_openid")),
                instant(first(text(data, "timestamp"), text(data, "event_time"))), data, event.raw());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String first(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (Exception ignored) { return null; }
    }
}
