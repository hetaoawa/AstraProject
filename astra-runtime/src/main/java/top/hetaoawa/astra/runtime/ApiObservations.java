package top.hetaoawa.astra.runtime;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

final class ApiObservations implements OutboundQueue.Observer {
  private final JdbcDirectory directory;
  private final JdbcHistory history;

  ApiObservations(JdbcDirectory directory, JdbcHistory history) {
    this.directory = directory;
    this.history = history;
  }

  public void before(OutboundQueue.Job job) {
    if (!history.enabled()) return;
    var event = outgoing(job, "outbound:" + job.id());
    if (event != null) {
      history.save(event, true);
      history.status(event.key(), "QUEUED");
    }
  }

  public void after(OutboundQueue.Job job, ApiResponse response, Throwable error) {
    if (error != null) {
      String state =
          error instanceof PlatformException p && !p.outcomeKnown() ? "OUTCOME_UNKNOWN" : "FAILED";
      history.status(Keys.join("outbound", job.id()), state);
      return;
    }
    accept(job, response);
    history.status(Keys.join("outbound", job.id()), "HTTP_ACCEPTED");
  }

  private MessageEvent outgoing(OutboundQueue.Job job, String messageId) {
    if (!job.method().equals("POST")
        || !(job.path().endsWith("/messages") || job.path().endsWith("/stream_messages")))
      return null;
    String[] p = job.path().split("/");
    if (p.length < 4) return null;
    Scene scene =
        p[1].equals("channels")
            ? Scene.CHANNEL
            : p[1].equals("dms")
                ? Scene.CHANNEL_DM
                : p.length > 4 && p[2].equals("groups") ? Scene.GROUP : Scene.C2C;
    String id = URLDecoder.decode(p[1].equals("v2") ? p[3] : p[2], StandardCharsets.UTF_8);
    var chat = new ChatRef(job.bot(), scene, id, scene == Scene.CHANNEL_DM ? id : "");
    var segments = segments(job);
    String text = QqJson.string(job.body().getOrDefault("content", job.body().get("content_raw")));
    return new MessageEvent(
        new EventData(
            Keys.join("outbound", job.id()),
            "API_MESSAGE_SENT",
            "",
            chat,
            null,
            Instant.now(),
            job.body()),
        new Message(
            messageId,
            "",
            text,
            segments,
            false,
            job.body(),
            QqMessageParser.parse(job.bot(), scene, job.body(), Keys.join("outbound", job.id()))));
  }

  private List<Message.Segment> segments(OutboundQueue.Job job) {
    var result = new ArrayList<Message.Segment>();
    job.body()
        .forEach(
            (key, value) -> {
              if (value != null) result.add(new Message.Segment(key, Map.of("value", value)));
            });
    job.files()
        .forEach(
            (field, file) ->
                result.add(
                    new Message.Segment(
                        "attachment",
                        Map.of(
                            "field",
                            field,
                            "filename",
                            file.name(),
                            "content_type",
                            file.contentType(),
                            "size",
                            file.data().length))));
    return List.copyOf(result);
  }

  void accept(OutboundQueue.Job job, ApiResponse response) {
    if (job.path().endsWith("/stream_messages")) {
      String mid = QqJson.string(response.data().get("id"));
      if (!mid.isBlank()) {
        var event = outgoing(job, mid);
        history.removeAttempt(Keys.join("outbound", job.id()), "outbound:" + job.id());
        history.saveStream(event, QqJson.string(job.body().getOrDefault("input_mode", "append")));
      }
    }
    String[] path = job.path().split("/");
    for (int i = 0; i < path.length; i++)
      path[i] = URLDecoder.decode(path[i], StandardCharsets.UTF_8);
    Instant now = Instant.now();
    var data = response.data();
    if (job.method().equals("DELETE")
        && path.length >= 5
        && path[path.length - 2].equals("messages")) {
      Scene scene =
          path[1].equals("channels")
              ? Scene.CHANNEL
              : path[1].equals("dms")
                  ? Scene.CHANNEL_DM
                  : path.length == 6 && path[2].equals("groups") ? Scene.GROUP : Scene.C2C;
      String id = path[1].equals("v2") ? path[3] : path[2];
      history.recallSucceeded(
          new ChatRef(job.bot(), scene, id, scene == Scene.CHANNEL_DM ? id : ""),
          path[path.length - 1],
          now,
          Keys.join("outbound", job.id()));
    }
    if (job.method().equals("POST") && job.path().endsWith("/messages") && path.length >= 4) {
      Scene scene =
          path[1].equals("channels")
              ? Scene.CHANNEL
              : path[1].equals("dms")
                  ? Scene.CHANNEL_DM
                  : path.length > 4 && path[2].equals("groups") ? Scene.GROUP : Scene.C2C;
      String id = path[1].equals("v2") ? path[3] : path[2];
      String mid = QqJson.string(data.get("id"));
      if (mid.isBlank()) return;
      var chat = new ChatRef(job.bot(), scene, id, scene == Scene.CHANNEL_DM ? id : "");
      String text = QqJson.string(job.body().get("content"));
      List<Message.Segment> segments = segments(job);
      var observed = new LinkedHashMap<>(job.body());
      observed.putAll(data);
      history.removeAttempt(Keys.join("outbound", job.id()), "outbound:" + job.id());
      history.save(
          new MessageEvent(
              new EventData(
                  Keys.join("outbound", job.id()),
                  "API_MESSAGE_SENT",
                  "",
                  chat,
                  null,
                  now,
                  job.body()),
              new Message(
                  mid,
                  QqEventCodec.index(data),
                  text,
                  segments,
                  false,
                  observed,
                  QqMessageParser.parse(
                      job.bot(), scene, observed, Keys.join("outbound", job.id())))),
          true);
    }
    if (path.length == 3 && path[1].equals("channels") && job.method().equals("PATCH"))
      directory.upsert(
          new Identity(job.bot(), EntityType.CHANNEL, "channel_id", path[2]),
          data,
          "API:" + job.path(),
          now,
          null,
          null,
          true);
    if (path.length == 4
        && path[1].equals("guilds")
        && path[3].equals("channels")
        && job.method().equals("POST")) {
      String channel = QqJson.string(data.get("id"));
      if (!channel.isBlank())
        directory.upsert(
            new Identity(job.bot(), EntityType.CHANNEL, "channel_id", channel),
            data,
            "API:" + job.path(),
            now,
            null,
            null,
            true);
    }
    if (!job.method().equals("GET")) return;
    if (job.path().equals("/users/@me")) {
      String user = QqJson.string(data.get("id"));
      if (!user.isBlank())
        directory.upsert(
            new Identity(job.bot(), EntityType.USER, "guild_user_id", user),
            data,
            "API:" + job.path(),
            now,
            null,
            null,
            true);
    }
    if (path.length >= 5 && path[1].equals("v2") && path[2].equals("groups")) {
      var chat = new ChatRef(job.bot(), Scene.GROUP, path[3], "");
      if (path[4].equals("info"))
        directory.upsert(
            new Identity(job.bot(), EntityType.GROUP, "group_openid", path[3]),
            data,
            "API:" + job.path(),
            now,
            QqJson.string(data.get("union_group_openid")),
            null,
            true);
      if (path[4].equals("members")) {
        if (path.length == 6) member(job.bot(), chat, "member_openid", data, path[5], now);
        else
          for (var m : list(data, "members")) member(job.bot(), chat, "member_openid", m, "", now);
      }
    }
    if (path.length >= 3 && path[1].equals("guilds")) {
      String guild = path[2];
      if (path.length == 3)
        directory.upsert(
            new Identity(job.bot(), EntityType.GUILD, "guild_id", guild),
            data,
            "API:" + job.path(),
            now,
            null,
            null,
            true);
      if (path.length >= 4 && path[3].equals("members")) {
        var chat = new ChatRef(job.bot(), Scene.SYSTEM, guild, guild);
        if (path.length == 5) member(job.bot(), chat, "guild_user_id", data, path[4], now);
        else for (var m : list(data, "items")) member(job.bot(), chat, "guild_user_id", m, "", now);
      }
      if (path.length == 4 && path[3].equals("channels"))
        for (var c : list(data, "items")) {
          String id = QqJson.string(c.get("id"));
          if (!id.isBlank())
            directory.upsert(
                new Identity(job.bot(), EntityType.CHANNEL, "channel_id", id),
                c,
                "API:" + job.path(),
                now,
                null,
                null,
                true);
        }
    }
    if (path.length == 3 && path[1].equals("channels"))
      directory.upsert(
          new Identity(job.bot(), EntityType.CHANNEL, "channel_id", path[2]),
          data,
          "API:" + job.path(),
          now,
          null,
          null,
          true);
    if (job.path().equals("/users/@me/guilds"))
      for (var g : list(data, "items")) {
        String id = QqJson.string(g.get("id"));
        if (!id.isBlank())
          directory.upsert(
              new Identity(job.bot(), EntityType.GUILD, "guild_id", id),
              g,
              "API:" + job.path(),
              now,
              null,
              null,
              true);
      }
  }

  private void member(
      BotId bot,
      ChatRef chat,
      String kind,
      Map<String, Object> data,
      String fallback,
      Instant now) {
    var user = data.get("user") instanceof Map<?, ?> ? QqJson.object(data.get("user")) : data;
    String id =
        QqJson.string(
            user.getOrDefault(kind.equals("member_openid") ? "member_openid" : "id", fallback));
    if (id.isBlank()) return;
    var fields = new LinkedHashMap<>(user);
    for (String field : List.of("nick", "roles", "joined_at", "member_role"))
      if (data.containsKey(field)) fields.put(field, data.get(field));
    directory.upsert(
        new Identity(bot, EntityType.USER, kind, id),
        fields,
        "API:member",
        now,
        QqJson.string(user.get("union_openid")),
        chat,
        true);
  }

  private List<Map<String, Object>> list(Map<String, Object> data, String key) {
    Object value = data.get(key);
    if (value == null && data.get("data") instanceof Map<?, ?> nested) value = nested.get(key);
    return value instanceof List<?> list ? list.stream().map(QqJson::object).toList() : List.of();
  }
}
