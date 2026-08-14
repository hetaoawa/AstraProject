package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** Raw event envelope shared by WebSocket and Webhook deliveries. */
/** Raw Gateway or Webhook event envelope. */
public record QQEvent(
        String id,
        int op,
        Long sequence,
        String type,
        JsonNode data,
        JsonNode raw
) {
    /** Returns whether this envelope is a normal Gateway Dispatch event. */
    public boolean isDispatch() {
        return op == 0;
    }
}
