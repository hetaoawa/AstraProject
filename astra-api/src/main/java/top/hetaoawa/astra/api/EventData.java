package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.Map;

public record EventData(
    String key,
    String platformType,
    String platformEventId,
    ChatRef chat,
    Identity actor,
    Instant occurredAt,
    Map<String, Object> raw) {
  public EventData {
    raw =
        raw == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    occurredAt = occurredAt == null ? Instant.now() : occurredAt;
  }

  public BotId bot() {
    return chat.bot();
  }
}
