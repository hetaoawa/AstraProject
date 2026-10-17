package top.hetaoawa.astra.qq;

import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;

final class QqMemberEvents {
  static GroupJoinRequest request(BotId bot, Map<String, Object> raw) {
    var applicant = QqMessageParser.member(bot, Scene.GROUP, raw, Map.of());
    String inviter = string(raw, "invited_by");
    var auto = QqJson.object(raw.get("auto_approved"));
    return new GroupJoinRequest(
        string(raw, "group_openid"),
        string(raw, "join_request_id"),
        string(raw, "risk_tips"),
        string(raw, "union_openid"),
        string(raw, "member_openid"),
        string(raw, "username"),
        time(raw.get("apply_at")),
        string(raw, "apply_source"),
        inviter,
        bool(raw.get("bot")),
        verification(raw.get("verify_info")),
        auto.isEmpty()
            ? null
            : new GroupJoinRequest.AutoApproved(string(auto, "strategy_id"), auto),
        applicant,
        member(bot, Scene.GROUP, inviter),
        operator(bot, Scene.GROUP, raw),
        raw);
  }

  static MemberChange change(
      BotId bot, Scene scene, String type, Map<String, Object> raw, Instant timestamp) {
    var user = QqJson.object(raw.get("user"));
    var target =
        QqMessageParser.member(
            bot, scene, scene == Scene.GROUP ? raw : user, scene == Scene.GROUP ? Map.of() : raw);
    return new MemberChange(
        type.endsWith("ADD")
            ? MemberChange.Kind.JOINED
            : type.endsWith("REMOVE") ? MemberChange.Kind.LEFT : MemberChange.Kind.UPDATED,
        string(raw, "group_openid"),
        string(raw, "guild_id"),
        string(raw, "user_openid"),
        timestamp,
        target,
        operator(bot, scene, raw),
        string(raw, "join_request_id"),
        verification(raw.get("verify_info")),
        raw);
  }

  private static PlatformMember operator(BotId bot, Scene scene, Map<String, Object> raw) {
    var op = QqJson.object(raw.get("op_user"));
    if (!op.isEmpty()) return QqMessageParser.member(bot, scene, op, Map.of());
    String id = scene == Scene.GROUP ? string(raw, "op_member_openid") : string(raw, "op_user_id");
    return member(bot, scene, id);
  }

  private static PlatformMember member(BotId bot, Scene scene, String id) {
    if (id == null || id.isBlank()) return null;
    return QqMessageParser.member(
        bot, scene, Map.of(scene == Scene.GROUP ? "member_openid" : "id", id), Map.of());
  }

  private static GroupJoinRequest.Verification verification(Object value) {
    if (!(value instanceof Map<?, ?>)) return null;
    var raw = QqJson.object(value);
    var qa = new ArrayList<GroupJoinRequest.ReviewQA>();
    if (raw.get("review_qa_list") instanceof List<?> list)
      for (Object item : list) {
        var row = QqJson.object(item);
        qa.add(new GroupJoinRequest.ReviewQA(string(row, "question"), string(row, "answer")));
      }
    return new GroupJoinRequest.Verification(
        string(raw, "method"), string(raw, "verify_message"), qa);
  }

  private static String string(Map<String, Object> raw, String field) {
    return raw.get(field) == null ? null : QqJson.string(raw.get(field));
  }

  private static Boolean bool(Object value) {
    return value instanceof Boolean b ? b : null;
  }

  private static Instant time(Object value) {
    try {
      return OffsetDateTime.parse(QqJson.string(value)).toInstant();
    } catch (Exception e) {
      return null;
    }
  }
}
