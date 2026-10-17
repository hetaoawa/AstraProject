package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.Map;

public record MemberRelation(
    ChatRef chat,
    Identity member,
    Map<String, Object> fields,
    String source,
    Instant observedAt,
    boolean active,
    boolean complete) {
  public MemberRelation {
    fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
  }
}
