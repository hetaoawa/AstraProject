package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.Map;

public record Profile(
    Identity identity,
    Map<String, Object> fields,
    String source,
    Instant firstSeen,
    Instant lastSeen,
    boolean complete) {}
