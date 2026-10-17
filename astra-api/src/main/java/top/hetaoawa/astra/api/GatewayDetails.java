package top.hetaoawa.astra.api;

import java.util.List;
import java.util.Map;

public record GatewayDetails(
    Integer opcode,
    Long sequence,
    Long heartbeatInterval,
    Integer version,
    String sessionId,
    PlatformMember user,
    List<Integer> shard,
    String plainToken,
    String eventTs,
    Object payload,
    Map<String, Object> raw) {}
