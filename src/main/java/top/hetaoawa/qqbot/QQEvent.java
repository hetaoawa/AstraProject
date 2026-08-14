package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw event envelope shared by WebSocket and Webhook deliveries. */
public record QQEvent(
        String id,
        int op,
        Long sequence,
        String type,
        JsonNode data,
        JsonNode raw
) {
    public boolean isDispatch() {
        return op == 0;
    }
}
