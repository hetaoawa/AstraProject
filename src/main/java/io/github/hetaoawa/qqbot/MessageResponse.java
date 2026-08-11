package io.github.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

public record MessageResponse(String id, String timestamp, JsonNode raw) {
}
