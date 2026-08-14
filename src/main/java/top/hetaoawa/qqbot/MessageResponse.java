package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** Result returned after a message or interaction response is accepted by QQ. */
public record MessageResponse(String id, String timestamp, JsonNode raw) {
}
