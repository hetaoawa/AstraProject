package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Platform User fields plus the member fields supplied for channel membership. Null means unknown.
 */
public record PlatformMember(
    Identity identity,
    String id,
    String username,
    String avatar,
    Boolean bot,
    Boolean isYou,
    String scope,
    String unionOpenid,
    String unionUserAccount,
    String userOpenid,
    String memberOpenid,
    String memberRole,
    String nick,
    List<String> roles,
    Instant joinedAt,
    String memberNickname,
    Map<String, Object> raw) {
  public PlatformMember(
      Identity identity,
      String id,
      String username,
      String avatar,
      Boolean bot,
      Boolean isYou,
      String scope,
      String unionOpenid,
      String unionUserAccount,
      String userOpenid,
      String memberOpenid,
      String memberRole,
      String nick,
      List<String> roles,
      Instant joinedAt,
      Map<String, Object> raw) {
    this(
        identity,
        id,
        username,
        avatar,
        bot,
        isYou,
        scope,
        unionOpenid,
        unionUserAccount,
        userOpenid,
        memberOpenid,
        memberRole,
        nick,
        roles,
        joinedAt,
        raw != null && raw.get("member_nickname") instanceof String name ? name : null,
        raw);
  }

  public PlatformMember {
    if (memberNickname == null && raw != null && raw.get("member_nickname") instanceof String name)
      memberNickname = name;
    roles = roles == null ? List.of() : List.copyOf(roles);
    raw =
        raw == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
  }
}
