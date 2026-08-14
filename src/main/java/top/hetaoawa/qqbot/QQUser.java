package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** User shape used by private and group message events. */
public record QQUser(
        String id,
        String username,
        boolean bot,
        String userOpenId,
        String memberOpenId,
        String memberRole,
        JsonNode raw
) {
}
