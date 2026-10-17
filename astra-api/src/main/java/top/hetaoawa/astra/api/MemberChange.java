package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.Map;

/** Operator is unknown unless the platform explicitly identifies one. */
public record MemberChange(
    Kind kind,
    String groupOpenid,
    String guildId,
    String userOpenid,
    Instant timestamp,
    PlatformMember target,
    PlatformMember operator,
    String joinRequestId,
    GroupJoinRequest.Verification verifyInfo,
    Map<String, Object> raw) {
  public enum Kind {
    JOINED,
    LEFT,
    UPDATED
  }
}
