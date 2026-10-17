package top.hetaoawa.astra.api;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

public final class Moderation {
  private final PlatformApi api;

  public Moderation(PlatformApi api) {
    this.api = api;
  }

  public enum MuteOperation implements RequestPayload.WireValue {
    ADD,
    UPDATE,
    DELETE;

    public Object wireValue() {
      return this == DELETE ? "del" : name().toLowerCase(Locale.ROOT);
    }
  }

  public enum BlacklistOperation implements RequestPayload.WireValue {
    ADD,
    DELETE;

    public Object wireValue() {
      return this == DELETE ? "del" : "add";
    }
  }

  public enum Approval implements RequestPayload.WireValue {
    APPROVE,
    DECLINE;

    public Object wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public record MemberMute(MuteOperation op, String memberOpenid, OffsetDateTime muteExpireAt)
      implements RequestPayload {
    public MemberMute {
      Objects.requireNonNull(op);
      id(memberOpenid);
      if (op != MuteOperation.DELETE && muteExpireAt == null)
        throw new IllegalArgumentException("Mute expiry required");
    }
  }

  public record GroupMute(List<MemberMute> members) implements RequestPayload {
    public GroupMute {
      members = List.copyOf(members);
      size(members);
    }
  }

  public record RemoveMembers(List<String> memberOpenids, boolean addToMemberBlacklist)
      implements RequestPayload {
    public RemoveMembers {
      memberOpenids = ids(memberOpenids);
    }
  }

  public record Blacklist(BlacklistOperation op, List<String> memberOpenids)
      implements RequestPayload {
    public Blacklist {
      Objects.requireNonNull(op);
      memberOpenids = ids(memberOpenids);
    }
  }

  public record JoinApproval(
      Approval op, String joinRequestId, String rejectReason, Boolean addToMemberBlacklist)
      implements RequestPayload {
    public JoinApproval {
      Objects.requireNonNull(op);
      if (op == Approval.APPROVE
          && (rejectReason != null || Boolean.TRUE.equals(addToMemberBlacklist)))
        throw new IllegalArgumentException("Reject options require decline");
    }
  }

  public record GuildMute(String muteEndTimestamp, String muteSeconds) implements RequestPayload {
    public GuildMute {
      if (muteEndTimestamp == null && muteSeconds == null)
        throw new IllegalArgumentException("Mute expiry or seconds required");
      for (String value : Arrays.asList(muteEndTimestamp, muteSeconds))
        if (value != null && !value.matches("[0-9]+"))
          throw new IllegalArgumentException("Nonnegative integer string required");
    }

    public static GuildMute seconds(Duration duration) {
      if (duration.isNegative()) throw new IllegalArgumentException("Negative mute duration");
      return new GuildMute(null, Long.toString(duration.toSeconds()));
    }
  }

  public record GuildKick(boolean addBlacklist, int deleteHistoryMsgDays)
      implements RequestPayload {
    public GuildKick {
      if (!Set.of(-1, 0, 3, 7, 15, 30).contains(deleteHistoryMsgDays))
        throw new IllegalArgumentException("Unsupported message deletion window");
    }
  }

  public record RemoveResult(
      String removeMembersResult,
      List<String> addToMemberBlacklistFailOpenids,
      ApiResponse response) {}

  public record BlacklistResult(List<String> failOpenids, ApiResponse response) {}

  public CompletionStage<ApiResponse> muteGroup(String group, GroupMute request) {
    return call(
        "v2_groups_group_openid_restrict_chat_setting.post",
        Map.of("group_openid", id(group)),
        request);
  }

  public CompletionStage<RemoveResult> removeMembers(String group, RemoveMembers request) {
    return call(
            "v2_groups_group_openid_batch_remove_members.post",
            Map.of("group_openid", id(group)),
            request)
        .thenApply(
            r ->
                new RemoveResult(
                    string(r.data().get("remove_members_result")),
                    strings(r.data().get("add_to_member_blacklist_fail_openids")),
                    r));
  }

  public CompletionStage<RemoveResult> kick(String group, List<String> members) {
    return removeMembers(group, new RemoveMembers(members, false));
  }

  public CompletionStage<RemoveResult> kickAndBlacklist(String group, List<String> members) {
    return removeMembers(group, new RemoveMembers(members, true));
  }

  public CompletionStage<BlacklistResult> blacklist(String group, Blacklist request) {
    return call(
            "v2_groups_group_openid_member_blacklist.post",
            Map.of("group_openid", id(group)),
            request)
        .thenApply(r -> new BlacklistResult(strings(r.data().get("fail_openids")), r));
  }

  public CompletionStage<ApiResponse> approveJoin(
      String group, String member, JoinApproval request) {
    return call(
        "v2_groups_group_openid_approval_join_request_member_openid.post",
        Map.of("group_openid", id(group), "member_openid", id(member)),
        request);
  }

  public CompletionStage<ApiResponse> muteGuildMember(
      String guild, String user, GuildMute request) {
    return call(
        "patch_guild_member_mute", Map.of("guild_id", id(guild), "user_id", id(user)), request);
  }

  public CompletionStage<ApiResponse> kickGuildMember(
      String guild, String user, GuildKick request) {
    return call("delete_member", Map.of("guild_id", id(guild), "user_id", id(user)), request);
  }

  private CompletionStage<ApiResponse> call(
      String operation, Map<String, String> path, RequestPayload body) {
    return api.call(operation, path, Map.of(), body.toMap());
  }

  private static String id(String id) {
    if (id == null || id.isBlank())
      throw new IllegalArgumentException("Nonempty platform identifier required");
    return id;
  }

  private static void size(List<?> members) {
    if (members.isEmpty() || members.size() > 20)
      throw new IllegalArgumentException("Between 1 and 20 members required");
  }

  private static List<String> ids(List<String> members) {
    size(members);
    members.forEach(Moderation::id);
    return List.copyOf(members);
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> list
        ? list.stream().map(Moderation::string).toList()
        : List.of();
  }
}
