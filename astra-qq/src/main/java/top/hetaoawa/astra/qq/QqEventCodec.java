package top.hetaoawa.astra.qq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class QqEventCodec implements EventCodec {
  public String encode(Event event) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("kind", event.getClass().getSimpleName());
    json.put("data", event.data());
    if (event instanceof MessageEvent m) json.put("message", m.message());
    if (event instanceof RequestEvent r) json.put("request", r.request());
    if (event instanceof NoticeEvent n) {
      json.put("memberChange", n.memberChange());
      if (n.details() != null) {
        json.put("detailsKind", n.details().getClass().getSimpleName());
        json.put("details", n.details());
      }
    }
    if (event instanceof MetaEvent m) json.put("details", m.details());
    if (event instanceof MessageRecallEvent r) {
      json.put("messageId", r.messageId());
      json.put("messageIndex", r.messageIndex());
      json.put("originalMessage", r.originalMessage());
      json.put("operator", r.operator());
    }
    return QqJson.write(json);
  }

  public Event decode(String json) {
    try {
      var node = QqJson.MAPPER.readTree(json);
      var data = QqJson.MAPPER.treeToValue(node.get("data"), EventData.class);
      return switch (node.get("kind").asText()) {
        case "MessageEvent" ->
            new MessageEvent(data, QqJson.MAPPER.treeToValue(node.get("message"), Message.class));
        case "MessageRecallEvent" ->
            new MessageRecallEvent(
                data,
                node.get("messageId").asText(),
                node.path("messageIndex").asText(""),
                node.hasNonNull("originalMessage")
                    ? QqJson.MAPPER.treeToValue(node.get("originalMessage"), Message.class)
                    : null,
                node.hasNonNull("operator")
                    ? QqJson.MAPPER.treeToValue(node.get("operator"), PlatformMember.class)
                    : null);
        case "NoticeEvent" ->
            new NoticeEvent(
                data,
                node.hasNonNull("memberChange")
                    ? QqJson.MAPPER.treeToValue(node.get("memberChange"), MemberChange.class)
                    : null,
                node.hasNonNull("details")
                    ? QqJson.MAPPER.treeToValue(
                        node.get("details"),
                        QqNoticeParser.storedType(node.path("detailsKind").asText()))
                    : null);
        case "RequestEvent" ->
            new RequestEvent(
                data,
                node.hasNonNull("request")
                    ? QqJson.MAPPER.treeToValue(node.get("request"), GroupJoinRequest.class)
                    : null);
        case "MetaEvent" ->
            new MetaEvent(
                data,
                node.hasNonNull("details")
                    ? QqJson.MAPPER.treeToValue(node.get("details"), GatewayDetails.class)
                    : null);
        default -> new RawEvent(data);
      };
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid queued event", e);
    }
  }

  public Event normalize(BotId bot, Map<String, Object> payload) {
    String type = QqJson.string(payload.get("t"));
    long opcode = QqJson.number(payload.get("op"), 0);
    if (type.isBlank() && opcode != 0) type = "OPCODE_" + opcode;
    var raw = QqJson.object(payload.get("d"));
    var target = raw.get("message") instanceof Map<?, ?> ? QqJson.object(raw.get("message")) : raw;
    String group = QqJson.string(raw.get("group_openid")),
        channel = QqJson.string(target.get("channel_id")),
        guild = QqJson.string(target.get("guild_id"));
    var author = QqJson.object(target.get("author"));
    var user = QqJson.object(raw.get("user"));
    var resolved = QqJson.object(QqJson.object(raw.get("data")).get("resolved"));
    Scene scene;
    String chatId;
    if (type.startsWith("DIRECT_MESSAGE_")) {
      scene = Scene.CHANNEL_DM;
      chatId = guild;
    } else if (!group.isBlank()) {
      scene = Scene.GROUP;
      chatId = group;
    } else if (!channel.isBlank() || type.startsWith("CHANNEL_")) {
      scene = Scene.CHANNEL;
      chatId = channel.isBlank() ? QqJson.string(raw.get("id")) : channel;
    } else if (type.startsWith("C2C_") || type.startsWith("FRIEND_")) {
      scene = Scene.C2C;
      chatId =
          first(
              author.get("user_openid"),
              raw.get("openid"),
              raw.get("user_openid"),
              author.get("id"));
    } else if (type.equals("INTERACTION_CREATE")) {
      long chatType = QqJson.number(raw.get("chat_type"), -1);
      scene =
          chatType == 1
              ? Scene.GROUP
              : chatType == 2 ? Scene.C2C : chatType == 0 ? Scene.CHANNEL : Scene.SYSTEM;
      chatId =
          scene == Scene.GROUP
              ? group
              : scene == Scene.C2C ? QqJson.string(raw.get("user_openid")) : channel;
    } else {
      scene = Scene.SYSTEM;
      chatId = first(raw.get("guild_id"), "bot");
    }
    ChatRef chat = new ChatRef(bot, scene, chatId.isBlank() ? "bot" : chatId, guild);
    String actorId =
        scene == Scene.GROUP
            ? first(
                author.get("member_openid"),
                raw.get("member_openid"),
                raw.get("op_member_openid"),
                raw.get("group_member_openid"),
                author.get("id"))
            : scene == Scene.C2C
                ? first(
                    author.get("user_openid"),
                    raw.get("openid"),
                    raw.get("user_openid"),
                    author.get("id"))
                : first(
                    author.get("id"),
                    user.get("id"),
                    QqJson.object(raw.get("op_user")).get("id"),
                    raw.get("op_user_id"),
                    raw.get("user_id"),
                    raw.get("author_id"),
                    resolved.get("user_id"));
    Identity actor =
        actorId.isBlank()
            ? null
            : new Identity(
                bot,
                EntityType.USER,
                scene == Scene.GROUP
                    ? "member_openid"
                    : scene == Scene.C2C ? "user_openid" : "guild_user_id",
                actorId);
    boolean recall =
        Set.of("MESSAGE_DELETE", "PUBLIC_MESSAGE_DELETE", "DIRECT_MESSAGE_DELETE").contains(type);
    // The nested message timestamp describes sending, not the time of recall.
    Instant time =
        parseTime(
            recall
                ? QqJson.string(raw.get("timestamp"))
                : first(raw.get("timestamp"), target.get("timestamp")));
    String platformId = QqJson.string(payload.get("id"));
    String messageId = QqJson.string(target.get("id"));
    String index = index(target);
    boolean message =
        Set.of(
                "GROUP_AT_MESSAGE_CREATE",
                "GROUP_MESSAGE_CREATE",
                "C2C_MESSAGE_CREATE",
                "AT_MESSAGE_CREATE",
                "MESSAGE_CREATE",
                "DIRECT_MESSAGE_CREATE")
            .contains(type);
    String key =
        message
            ? Keys.join("message", chat.key(), messageId, index)
            : Keys.join(
                "event",
                chat.key(),
                type,
                platformId.isBlank() ? hash(QqJson.write(raw)) : platformId);
    EventData data = new EventData(key, type, platformId, chat, actor, time, raw);
    if (message) {
      if (messageId.isBlank()) throw new IllegalArgumentException("Message ID missing");
      var segments = new ArrayList<Message.Segment>();
      String text = QqJson.string(raw.get("content"));
      if (!text.isEmpty()) segments.add(new Message.Segment("text", Map.of("text", text)));
      for (String field : List.of("attachments", "mentions", "msg_elements"))
        if (raw.get(field) instanceof List<?> list)
          for (Object item : list) segments.add(new Message.Segment(field, QqJson.object(item)));
      for (String field : List.of("ark_data", "embeds", "message_reference"))
        if (raw.containsKey(field))
          segments.add(
              new Message.Segment(
                  field,
                  raw.get(field) instanceof Map<?, ?>
                      ? QqJson.object(raw.get(field))
                      : Map.of("value", raw.get(field))));
      boolean toMe = scene == Scene.C2C || scene == Scene.CHANNEL_DM || type.contains("AT_MESSAGE");
      return new MessageEvent(
          data,
          new Message(
              messageId,
              index,
              text,
              segments,
              toMe,
              raw,
              QqMessageParser.parse(bot, scene, raw, key)));
    }
    if (recall)
      return new MessageRecallEvent(
          data,
          messageId,
          index,
          new Message(
              messageId,
              index,
              QqJson.string(target.get("content")),
              List.of(),
              false,
              target,
              QqMessageParser.parse(bot, scene, target, key)),
          QqMessageParser.member(bot, scene, QqJson.object(raw.get("op_user")), Map.of()));
    if (type.equals("GROUP_JOIN_REQUEST"))
      return new RequestEvent(data, QqMemberEvents.request(bot, raw));
    if (Set.of(
            "GROUP_MEMBER_ADD",
            "GROUP_MEMBER_REMOVE",
            "GUILD_MEMBER_ADD",
            "GUILD_MEMBER_UPDATE",
            "GUILD_MEMBER_REMOVE")
        .contains(type))
      return new NoticeEvent(data, QqMemberEvents.change(bot, scene, type, raw, time));
    if (Set.of(
                "READY",
                "RESUMED",
                "BOT_CONNECTING",
                "BOT_DISCONNECTED",
                "FRAMEWORK_STARTED",
                "FRAMEWORK_STOPPING")
            .contains(type)
        || opcode != 0)
      return new MetaEvent(
          data,
          new GatewayDetails(
              (int) opcode,
              payload.get("s") == null ? null : QqJson.number(payload.get("s"), -1),
              raw.get("heartbeat_interval") == null
                  ? null
                  : QqJson.number(raw.get("heartbeat_interval"), -1),
              raw.get("version") == null && raw.get("v") == null
                  ? null
                  : (int) QqJson.number(raw.getOrDefault("version", raw.get("v")), -1),
              raw.get("session_id") == null ? null : QqJson.string(raw.get("session_id")),
              QqMessageParser.member(bot, Scene.SYSTEM, user, Map.of()),
              raw.get("shard") instanceof List<?> list
                  ? list.stream().map(v -> (int) QqJson.number(v, -1)).toList()
                  : List.of(),
              raw.get("plain_token") == null ? null : QqJson.string(raw.get("plain_token")),
              raw.get("event_ts") == null ? null : QqJson.string(raw.get("event_ts")),
              payload.get("d"),
              payload));
    if (type.startsWith("GUILD_")
        || type.startsWith("CHANNEL_")
        || type.startsWith("GROUP_")
        || type.startsWith("FRIEND_")
        || type.startsWith("C2C_MSG_")
        || type.startsWith("MESSAGE_AUDIT")
        || type.startsWith("MESSAGE_REACTION")
        || type.startsWith("AUDIO_")
        || type.startsWith("FORUM_")
        || type.startsWith("OPEN_FORUM_")
        || type.equals("INTERACTION_CREATE")
        || type.equals("SUBSCRIBE_MESSAGE_STATUS"))
      return new NoticeEvent(data, null, QqNoticeParser.parse(type, raw));
    return new RawEvent(data);
  }

  public static String index(Map<String, Object> body) {
    var scene = QqJson.object(body.get("message_scene"));
    if (scene.get("ext") instanceof List<?> list)
      for (Object entry : list) {
        String value = QqJson.string(entry);
        if (value.startsWith("msg_idx=")) return value.substring(8);
      }
    return first(QqJson.object(body.get("ext_info")).get("ref_idx"), body.get("msg_seq"));
  }

  private static String first(Object... values) {
    for (Object value : values) {
      String s = QqJson.string(value);
      if (!s.isBlank()) return s;
    }
    return "";
  }

  private static Instant parseTime(String value) {
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      try {
        return OffsetDateTime.parse(value).toInstant();
      } catch (Exception ignored) {
        long ts = QqJson.number(value, -1);
        return ts >= 0 ? Instant.ofEpochSecond(ts) : Instant.now();
      }
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
