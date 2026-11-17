package top.hetaoawa.astra.qq;

import java.time.*;
import java.util.*;
import java.util.regex.*;
import top.hetaoawa.astra.api.*;

/** Parses documented platform structure; unknown text formats stay intact. */
public final class QqMessageParser {
  private QqMessageParser() {}

  public static MessageContent parse(
      BotId bot, Scene scene, Map<String, Object> raw, String eventKey) {
    var context = QqJson.object(raw.get("message_scene"));
    var ext = strings(context.get("ext"));
    String ref = extValue(ext, "ref_msg_idx"), idx = QqEventCodec.index(raw);
    var elements = elements(bot, scene, raw.get("msg_elements"), eventKey, 0);
    var reference = QqJson.object(raw.get("message_reference"));
    Integer type = integer(raw.get("message_type"));
    String body = QqJson.string(raw.get("content"));
    MessageContent.Reference quote =
        Objects.equals(type, 103) || !ref.isEmpty() || !reference.isEmpty()
            ? new MessageContent.Reference(
                MessageIndex.observed(
                    ref.isEmpty() ? QqJson.string(reference.get("message_id")) : ref, eventKey),
                QqJson.string(reference.get("message_id")),
                bool(reference.get("ignore_get_message_error")),
                body,
                elements,
                body)
            : null;
    MessageContent.Merged merged =
        Objects.equals(type, 102)
            ? new MessageContent.Merged(elements, formatted(body), body)
            : null;
    var mentions = new ArrayList<PlatformMember>();
    if (raw.get("mentions") instanceof List<?> list)
      for (Object value : list) {
        var mention = member(bot, scene, QqJson.object(value), Map.of());
        if (mention != null) mentions.add(mention);
      }
    return new MessageContent(
        type,
        member(bot, scene, QqJson.object(raw.get("author")), QqJson.object(raw.get("member"))),
        mentions,
        attachments(raw.get("attachments")),
        ark(raw.get("ark_data")),
        elements,
        quote,
        merged,
        new MessageContent.SceneContext(
            QqJson.string(context.get("source")),
            ext,
            MessageIndex.observed(idx, eventKey),
            MessageIndex.observed(ref, eventKey),
            extValue(ext, "auth_token")),
        raw.get("parallel_message") instanceof Map<?, ?> || Objects.equals(type, 101)
            ? new MessageContent.Parallel(
                raw.get("parallel_message") instanceof Map<?, ?>
                    ? elements(
                        bot,
                        scene,
                        QqJson.object(raw.get("parallel_message")).get("msg_nodes"),
                        eventKey,
                        0)
                    : elements,
                raw.get("parallel_message") instanceof Map<?, ?>
                    ? QqJson.object(raw.get("parallel_message"))
                    : raw)
            : null,
        QqTypedData.read(MessageContent.Metadata.class, raw),
        raw.get("embeds") instanceof List<?> list
            ? list.stream()
                .map(v -> QqTypedData.read(MessageContent.Embed.class, QqJson.object(v)))
                .toList()
            : List.of(),
        raw.get("ark") instanceof Map<?, ?>
            ? QqTypedData.read(MessagePayload.Ark.class, QqJson.object(raw.get("ark")))
            : null);
  }

  public static PlatformMember member(
      BotId bot, Scene scene, Map<String, Object> user, Map<String, Object> membership) {
    if (user.isEmpty() && membership.isEmpty()) return null;
    String kind =
        scene == Scene.GROUP
            ? "member_openid"
            : scene == Scene.C2C ? "user_openid" : "guild_user_id";
    String id =
        QqJson.string(
            user.get(
                scene == Scene.GROUP
                    ? "member_openid"
                    : scene == Scene.C2C ? "user_openid" : "id"));
    if (id.isBlank()) id = QqJson.string(user.get("id"));
    var merged = new LinkedHashMap<>(user);
    merged.putAll(membership);
    return new PlatformMember(
        id.isBlank() ? null : new Identity(bot, EntityType.USER, kind, id),
        str(user, "id"),
        str(user, "username"),
        str(user, "avatar"),
        bool(user.get("bot")),
        bool(user.get("is_you")),
        str(user, "scope"),
        str(user, "union_openid"),
        str(user, "union_user_account"),
        str(user, "user_openid"),
        str(user, "member_openid"),
        str(user, "member_role"),
        str(membership, "nick"),
        strings(membership.get("roles")),
        instant(membership.get("joined_at")),
        str(user, "member_nickname"),
        merged);
  }

  private static List<MessageContent.Element> elements(
      BotId bot, Scene scene, Object value, String eventKey, int depth) {
    if (!(value instanceof List<?> list)) return List.of();
    if (depth > 64) throw new IllegalArgumentException("Message element nesting exceeds 64");
    var result = new ArrayList<MessageContent.Element>();
    for (Object item : list) {
      var raw = QqJson.object(item);
      String body = QqJson.string(raw.get("content"));
      result.add(
          new MessageContent.Element(
              MessageIndex.observed(str(raw, "msg_idx"), eventKey),
              member(bot, scene, QqJson.object(raw.get("author")), Map.of()),
              integer(raw.get("message_type")),
              body,
              attachments(raw.get("attachments")),
              ark(raw.get("ark_data")),
              elements(bot, scene, raw.get("msg_elements"), eventKey, depth + 1),
              formatted(body),
              raw));
    }
    return List.copyOf(result);
  }

  public static MessageContent.FormattedBody formatted(String value) {
    var matcher = Pattern.compile("(?m)^=== 消息 ([0-9]+) ===\\r?$").matcher(value);
    var starts = new ArrayList<Integer>();
    var ends = new ArrayList<Integer>();
    var ordinals = new ArrayList<Integer>();
    while (matcher.find()) {
      starts.add(matcher.start());
      ends.add(matcher.end());
      try {
        ordinals.add(Integer.parseInt(matcher.group(1)));
      } catch (NumberFormatException e) {
        return new MessageContent.FormattedBody(value, "", List.of(), false);
      }
    }
    if (starts.isEmpty()) return null;
    String title = value.substring(0, starts.getFirst()).strip();
    boolean complete = title.isEmpty() || title.equals("[群聊的聊天记录]");
    var entries = new ArrayList<MessageContent.FormattedEntry>();
    for (int i = 0; i < starts.size(); i++) {
      String original =
          value
              .substring(ends.get(i), i + 1 < starts.size() ? starts.get(i + 1) : value.length())
              .stripTrailing();
      var entry = entry(original, ordinals.get(i), 0, 0);
      complete &= ordinals.get(i) == i + 1 && entry.complete();
      entries.add(entry);
    }
    return new MessageContent.FormattedBody(value, title, entries, complete);
  }

  private static MessageContent.FormattedEntry entry(
      String original, int ordinal, int indent, int depth) {
    if (depth > 64)
      return new MessageContent.FormattedEntry(
          ordinal, original, null, null, false, List.of(), original, false);
    String prefix = " ".repeat(indent);
    var lines = original.split("\\r?\\n", -1);
    var content = new StringBuilder();
    String sender = null, type = null;
    boolean reading = false, seenContent = false, complete = true;
    var related = new ArrayList<MessageContent.FormattedEntry>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith(prefix + "[消息内容]")) {
        if (seenContent) complete = false;
        seenContent = true;
        reading = true;
        content.append(field(line.substring(indent + 6)));
      } else if (line.startsWith(prefix + "[发送者]")) {
        sender = field(line.substring(indent + 5));
        reading = false;
      } else if (line.startsWith(prefix + "[消息类型]")) {
        type = field(line.substring(indent + 6));
        reading = false;
      } else if (line.equals(prefix + "[关联消息]")) {
        reading = false;
        int start = i + 1;
        boolean any = false;
        Pattern marker = Pattern.compile("^" + Pattern.quote(prefix) + "--- 第([0-9]+)条 ---\\s*$");
        while (start < lines.length) {
          if (lines[start].isBlank()) {
            start++;
            continue;
          }
          var header = marker.matcher(lines[start]);
          if (!header.matches()) {
            complete = false;
            break;
          }
          int childOrdinal;
          try {
            childOrdinal = Integer.parseInt(header.group(1));
          } catch (NumberFormatException e) {
            complete = false;
            break;
          }
          int end = start + 1;
          while (end < lines.length && !marker.matcher(lines[end]).matches()) end++;
          var child =
              entry(
                  String.join("\n", Arrays.copyOfRange(lines, start + 1, end)),
                  childOrdinal,
                  indent + 4,
                  depth + 1);
          complete &= childOrdinal == related.size() + 1 && child.complete();
          related.add(child);
          any = true;
          start = end;
        }
        complete &= any;
        i = lines.length;
      } else if (reading) {
        content.append('\n').append(line.startsWith(prefix) ? line.substring(indent) : line);
      } else if (!line.isBlank()) complete = false;
    }
    String text = content.toString().stripTrailing();
    return new MessageContent.FormattedEntry(
        ordinal,
        text,
        sender,
        type,
        text.equals("[群聊的聊天记录]"),
        related,
        original,
        complete && seenContent);
  }

  private static String field(String value) {
    return value.startsWith(" ") ? value.substring(1) : value;
  }

  private static List<MessageContent.Attachment> attachments(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .map(QqJson::object)
        .map(
            m ->
                new MessageContent.Attachment(
                    str(m, "url"),
                    str(m, "filename"),
                    integer(m.get("width")),
                    integer(m.get("height")),
                    number(m.get("size")),
                    str(m, "content_type"),
                    str(m, "voice_wav_url"),
                    str(m, "asr_refer_text"),
                    m))
        .toList();
  }

  private static MessageContent.Ark ark(Object value) {
    if (!(value instanceof Map<?, ?>)) return null;
    var m = QqJson.object(value);
    return new MessageContent.Ark(
        str(m, "prompt"),
        str(m, "ark_type"),
        str(m, "ark_name"),
        QqJson.object(m.get("fields")),
        m);
  }

  private static String str(Map<String, Object> value, String key) {
    return value.get(key) == null ? null : QqJson.string(value.get(key));
  }

  private static Boolean bool(Object value) {
    return value instanceof Boolean b ? b : null;
  }

  private static Long number(Object value) {
    if (value == null) return null;
    try {
      return Long.valueOf(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer integer(Object value) {
    Long n = number(value);
    return n == null || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE ? null : n.intValue();
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> l ? l.stream().map(QqJson::string).toList() : List.of();
  }

  private static String extValue(List<String> ext, String key) {
    return ext.stream()
        .filter(s -> s.startsWith(key + "="))
        .map(s -> s.substring(key.length() + 1))
        .findFirst()
        .orElse("");
  }

  private static Instant instant(Object value) {
    try {
      return OffsetDateTime.parse(QqJson.string(value)).toInstant();
    } catch (Exception e) {
      return null;
    }
  }
}
