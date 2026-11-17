package top.hetaoawa.astra.storage;

import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class JdbcDirectory implements Directory, ObservationStore {
  private static final String DISSOLVED_SOURCE = "API:GROUP_DISSOLVED";
  private final JdbcDatabase db;
  private final Map<BotId, BotPolicy> policies = new HashMap<>();

  public JdbcDirectory(JdbcDatabase db, List<BotPolicy> list) {
    this.db = db;
    list.forEach(p -> policies.put(p.bot(), p));
  }

  private Profile profile(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Profile(
        JsonSupport.read(r.getString("identity_json"), Identity.class),
        JsonSupport.map(r.getString("fields_json")),
        r.getString("source"),
        Instant.ofEpochMilli(r.getLong("first_seen")),
        Instant.ofEpochMilli(r.getLong("last_seen")),
        r.getInt("complete_flag") != 0);
  }

  public Optional<Profile> find(Identity id) {
    return stored(id).filter(p -> !DISSOLVED_SOURCE.equals(p.source()));
  }

  private Optional<Profile> stored(Identity id) {
    return db
        .jdbc
        .query("SELECT * FROM astra_profiles WHERE identity_key=?", (r, n) -> profile(r), id.key())
        .stream()
        .findFirst();
  }

  public boolean isGroupDissolved(Identity id) {
    return id.type() == EntityType.GROUP
        && stored(id).map(p -> DISSOLVED_SOURCE.equals(p.source())).orElse(false);
  }

  private boolean isGroupDissolved(ChatRef chat) {
    return chat.scene() == Scene.GROUP
        && isGroupDissolved(new Identity(chat.bot(), EntityType.GROUP, "group_openid", chat.id()));
  }

  /** Retains only a durable exclusion marker so late observations cannot recreate the group. */
  public void dissolveGroup(Identity id) {
    if (id.type() != EntityType.GROUP || !id.kind().equals("group_openid"))
      throw new IllegalArgumentException("Expected group_openid group identity");
    db.transaction.executeWithoutResult(
        tx -> {
          var old = stored(id);
          long now = System.currentTimeMillis();
          if (old.isEmpty())
            db.jdbc.update(
                "INSERT INTO astra_profiles(identity_key,bot_key,entity_type,identity_json,"
                    + "fields_json,display_name,source,first_seen,last_seen,complete_flag)"
                    + " VALUES(?,?,?,?,'{}','',?,?,?,0)",
                id.key(),
                id.bot().key(),
                id.type().name(),
                JsonSupport.write(id),
                DISSOLVED_SOURCE,
                now,
                now);
          else
            db.jdbc.update(
                "UPDATE astra_profiles SET fields_json='{}',display_name='',source=?,"
                    + "last_seen=?,complete_flag=0 WHERE identity_key=?",
                DISSOLVED_SOURCE,
                Math.max(now, old.get().lastSeen().toEpochMilli()),
                id.key());
          db.jdbc.update(
              "DELETE FROM astra_members WHERE chat_key=?",
              new ChatRef(id.bot(), Scene.GROUP, id.value(), "").key());
          db.jdbc.update("DELETE FROM astra_union_links WHERE identity_key=?", id.key());
        });
  }

  public Page<Profile> search(BotId bot, EntityType type, String name, int offset, int limit) {
    validatePage(offset, limit);
    var args = new ArrayList<Object>(List.of(bot.key(), type.name(), DISSOLVED_SOURCE));
    String sql = "SELECT * FROM astra_profiles WHERE bot_key=? AND entity_type=? AND source<>?";
    if (name != null && !name.isBlank()) {
      sql += " AND display_name LIKE ? ESCAPE '#'";
      args.add(
          "%"
              + name.toLowerCase(Locale.ROOT)
                  .replace("#", "##")
                  .replace("%", "#%")
                  .replace("_", "#_")
              + "%");
    }
    sql += " ORDER BY identity_key LIMIT ? OFFSET ?";
    args.add(limit + 1);
    args.add(offset);
    return page(db.jdbc.query(sql, (r, n) -> profile(r), args.toArray()), offset, limit);
  }

  public Page<Profile> members(ChatRef chat, int offset, int limit) {
    validatePage(offset, limit);
    if (isGroupDissolved(chat)) return new Page<>(List.of(), offset, limit, false);
    return page(
        db.jdbc.query(
            "SELECT p.* FROM astra_profiles p JOIN astra_members m ON p.identity_key=m.member_key"
                + " WHERE m.chat_key=? AND m.active_flag=1 ORDER BY p.identity_key LIMIT ? OFFSET"
                + " ?",
            (r, n) -> profile(r),
            chat.key(),
            limit + 1,
            offset),
        offset,
        limit);
  }

  public Page<MemberRelation> relationships(ChatRef chat, boolean inactive, int offset, int limit) {
    validatePage(offset, limit);
    if (isGroupDissolved(chat)) return new Page<>(List.of(), offset, limit, false);
    return page(
        db.jdbc.query(
            "SELECT * FROM astra_members WHERE chat_key=?"
                + (inactive ? "" : " AND active_flag=1")
                + " ORDER BY member_key LIMIT ? OFFSET ?",
            (r, n) ->
                new MemberRelation(
                    JsonSupport.read(r.getString("chat_json"), ChatRef.class),
                    JsonSupport.read(r.getString("member_json"), Identity.class),
                    JsonSupport.map(r.getString("fields_json")),
                    r.getString("source"),
                    Instant.ofEpochMilli(r.getLong("last_seen")),
                    r.getInt("active_flag") != 0,
                    false),
            chat.key(),
            limit + 1,
            offset),
        offset,
        limit);
  }

  public Page<Profile> related(Identity id, int offset, int limit) {
    validatePage(offset, limit);
    if (isGroupDissolved(id)) return new Page<>(List.of(), offset, limit, false);
    var policy = policies.get(id.bot());
    if (policy == null
        || !policy.unionEnabled()
        || policy.unionScope() == null
        || policy.unionScope().isBlank()) return new Page<>(List.of(), offset, limit, false);
    var union =
        db.jdbc.query(
            "SELECT union_id FROM astra_union_links WHERE identity_key=? AND scope_name=?",
            (r, n) -> r.getString(1),
            id.key(),
            policy.unionScope());
    if (union.isEmpty()) return new Page<>(List.of(), offset, limit, false);
    Set<String> allowed = new HashSet<>();
    policies.values().stream()
        .filter(p -> p.unionEnabled() && Objects.equals(p.unionScope(), policy.unionScope()))
        .forEach(p -> allowed.add(p.bot().key()));
    var rows =
        db
            .jdbc
            .query(
                "SELECT p.* FROM astra_profiles p JOIN astra_union_links u ON"
                    + " p.identity_key=u.identity_key WHERE u.scope_name=? AND u.entity_type=? AND"
                    + " u.union_id=? AND p.source<>? ORDER BY p.identity_key",
                (r, n) -> profile(r),
                policy.unionScope(),
                id.type().name(),
                union.getFirst(),
                DISSOLVED_SOURCE)
            .stream()
            .filter(p -> allowed.contains(p.identity().bot().key()))
            .skip(offset)
            .limit(limit + 1)
            .toList();
    return page(rows, offset, limit);
  }

  public Map<String, Object> metadata(String plugin, Identity id) {
    return db
        .jdbc
        .query(
            "SELECT fields_json FROM astra_metadata WHERE plugin_id=? AND identity_key=?",
            (r, n) -> JsonSupport.map(r.getString(1)),
            plugin,
            id.key())
        .stream()
        .findFirst()
        .orElse(Map.of());
  }

  public void metadata(String plugin, Identity id, Map<String, Object> values) {
    db.transaction.executeWithoutResult(
        tx -> {
          int count =
              db.jdbc.update(
                  "UPDATE astra_metadata SET fields_json=? WHERE plugin_id=? AND identity_key=?",
                  JsonSupport.write(values),
                  plugin,
                  id.key());
          if (count == 0)
            db.jdbc.update(
                "INSERT INTO astra_metadata(plugin_id,identity_key,fields_json) VALUES(?,?,?)",
                plugin,
                id.key(),
                JsonSupport.write(values));
        });
  }

  public void upsert(
      Identity id,
      Map<String, Object> fields,
      String source,
      Instant observed,
      String union,
      ChatRef memberOf,
      boolean active) {
    db.transaction.executeWithoutResult(
        tx -> {
          var old = stored(id);
          if (old.map(p -> DISSOLVED_SOURCE.equals(p.source())).orElse(false)) return;
          Map<String, Object> merged =
              new LinkedHashMap<>(old.map(Profile::fields).orElse(Map.of()));
          if (memberOf != null) merged.remove("member_nickname");
          Set<String> relationshipFields =
              Set.of("nick", "member_nickname", "roles", "member_role", "joined_at");
          fields.forEach(
              (k, v) -> {
                if ((memberOf == null || !relationshipFields.contains(k))
                    && v != null
                    && (!(v instanceof String s) || !s.isBlank())
                    && (old.isEmpty()
                        || !observed.isBefore(old.get().lastSeen())
                        || !merged.containsKey(k))) merged.put(k, v);
              });
          long time = observed.toEpochMilli(),
              first = old.map(p -> Math.min(time, p.firstSeen().toEpochMilli())).orElse(time),
              last = old.map(p -> Math.max(time, p.lastSeen().toEpochMilli())).orElse(time);
          String display =
              text(merged.getOrDefault(
                      "username",
                      merged.getOrDefault(
                          "name",
                          merged.getOrDefault("group_name", merged.getOrDefault("nick", "")))))
                  .toLowerCase(Locale.ROOT);
          if (old.isEmpty())
            db.jdbc.update(
                "INSERT INTO"
                    + " astra_profiles(identity_key,bot_key,entity_type,identity_json,fields_json,display_name,source,first_seen,last_seen,complete_flag)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,0)",
                id.key(),
                id.bot().key(),
                id.type().name(),
                JsonSupport.write(id),
                JsonSupport.write(merged),
                display,
                source,
                first,
                last);
          else
            db.jdbc.update(
                "UPDATE astra_profiles SET"
                    + " fields_json=?,display_name=?,source=?,first_seen=?,last_seen=? WHERE"
                    + " identity_key=? AND source<>?",
                JsonSupport.write(merged),
                display,
                source,
                first,
                last,
                id.key(),
                DISSOLVED_SOURCE);
          var policy = policies.get(id.bot());
          if (policy != null
              && policy.unionEnabled()
              && policy.unionScope() != null
              && !policy.unionScope().isBlank()
              && union != null
              && !union.isBlank()) {
            int count =
                db.jdbc.update(
                    "UPDATE astra_union_links SET scope_name=?,entity_type=?,union_id=? WHERE"
                        + " identity_key=?",
                    policy.unionScope(),
                    id.type().name(),
                    union,
                    id.key());
            if (count == 0)
              db.jdbc.update(
                  "INSERT INTO astra_union_links(identity_key,scope_name,entity_type,union_id)"
                      + " VALUES(?,?,?,?)",
                  id.key(),
                  policy.unionScope(),
                  id.type().name(),
                  union);
          }
          if (memberOf != null && !isGroupDissolved(memberOf)) {
            String relation = Keys.join(memberOf.key(), id.key());
            var membership = new LinkedHashMap<String, Object>();
            db
                .jdbc
                .query(
                    "SELECT fields_json FROM astra_members WHERE relation_key=?",
                    (r, n) -> JsonSupport.map(r.getString(1)),
                    relation)
                .stream()
                .findFirst()
                .ifPresent(membership::putAll);
            fields.forEach(
                (k, v) -> {
                  if (relationshipFields.contains(k)
                      && v != null
                      && (!(v instanceof String s) || !s.isBlank())) membership.put(k, v);
                });
            int count =
                db.jdbc.update(
                    "UPDATE astra_members SET active_flag=?,last_seen=?,fields_json=?,source=?"
                        + " WHERE relation_key=? AND last_seen<=?",
                    active ? 1 : 0,
                    time,
                    JsonSupport.write(membership),
                    source,
                    relation,
                    time);
            if (count == 0
                && db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM astra_members WHERE relation_key=?",
                        Integer.class,
                        relation)
                    == 0)
              db.jdbc.update(
                  "INSERT INTO"
                      + " astra_members(relation_key,chat_key,member_key,active_flag,last_seen,fields_json,source,chat_json,member_json)"
                      + " VALUES(?,?,?,?,?,?,?,?,?)",
                  relation,
                  memberOf.key(),
                  id.key(),
                  active ? 1 : 0,
                  time,
                  JsonSupport.write(membership),
                  source,
                  JsonSupport.write(memberOf),
                  JsonSupport.write(id));
          }
        });
  }

  @SuppressWarnings("unchecked")
  public void observe(Event e) {
    Map<String, Object> raw = e.data().raw();
    String type = e.data().platformType();
    Instant time = e.data().occurredAt();
    var chat = e.chat();
    if (chat.scene() == Scene.GROUP) {
      Map<String, Object> fields = new LinkedHashMap<>();
      if (raw.containsKey("group_name")) {
        fields.put("name", raw.get("group_name"));
        fields.put("group_name", raw.get("group_name"));
      }
      if (e instanceof MessageEvent || type.equals("GROUP_ADD_ROBOT"))
        fields.put("bot_present", true);
      if (type.equals("GROUP_DEL_ROBOT")) fields.put("bot_present", false);
      upsert(
          new Identity(e.bot(), EntityType.GROUP, "group_openid", chat.id()),
          fields,
          type,
          time,
          text(raw.get("union_group_openid")),
          null,
          true);
    }
    if (!chat.guildId().isBlank()
        && (chat.scene() == Scene.CHANNEL || chat.scene() == Scene.CHANNEL_DM))
      upsert(
          new Identity(e.bot(), EntityType.GUILD, "guild_id", chat.guildId()),
          Map.of(),
          type,
          time,
          null,
          null,
          true);
    if (chat.scene() == Scene.CHANNEL)
      upsert(
          new Identity(e.bot(), EntityType.CHANNEL, "channel_id", chat.id()),
          Map.of("guild_id", chat.guildId()),
          type,
          time,
          null,
          null,
          true);
    if (e.actor() != null) {
      Map<String, Object> author =
          raw.get("author") instanceof Map<?, ?> m
              ? (Map<String, Object>) m
              : raw.get("user") instanceof Map<?, ?> m
                  ? (Map<String, Object>) m
                  : new LinkedHashMap<>();
      Map<String, Object> fields = new LinkedHashMap<>(author);
      if (type.startsWith("GUILD_MEMBER"))
        for (String field : List.of("nick", "roles", "joined_at"))
          if (raw.containsKey(field)) fields.put(field, raw.get(field));
      if (raw.get("member") instanceof Map<?, ?> member) {
        if (member.get("nick") != null) fields.put("nick", member.get("nick"));
        if (member.get("roles") != null) fields.put("roles", member.get("roles"));
      }
      if (raw.get("member_role") != null) fields.put("member_role", raw.get("member_role"));
      ChatRef relation =
          type.startsWith("GUILD_MEMBER") && !chat.guildId().isBlank()
              ? new ChatRef(e.bot(), Scene.SYSTEM, chat.guildId(), chat.guildId())
              : !type.equals("GROUP_JOIN_REQUEST")
                      && (chat.scene() == Scene.GROUP || chat.scene() == Scene.CHANNEL)
                  ? chat
                  : null;
      upsert(
          e.actor(),
          fields,
          type,
          time,
          text(author.get("union_openid")),
          relation,
          !type.endsWith("MEMBER_REMOVE"));
    }
    if (e instanceof MessageEvent message && message.message().content() != null) {
      for (var mentioned : message.message().content().mentions())
        observeMember(
            mentioned,
            type,
            time,
            chat.scene() == Scene.GROUP || chat.scene() == Scene.CHANNEL ? chat : null,
            true);
    }
    if (e instanceof RequestEvent request && request.request() != null) {
      observeMember(request.request().applicant(), type, time, null, true);
      observeMember(request.request().inviter(), type, time, chat, true);
      observeMember(request.request().operator(), type, time, chat, true);
    }
    if (e instanceof NoticeEvent notice && notice.memberChange() != null) {
      var change = notice.memberChange();
      var relation =
          chat.scene() == Scene.GROUP
              ? chat
              : new ChatRef(e.bot(), Scene.SYSTEM, chat.guildId(), chat.guildId());
      observeMember(change.target(), type, time, relation, change.kind() != MemberChange.Kind.LEFT);
      if (change.target() == null
          || change.operator() == null
          || !Objects.equals(change.target().identity(), change.operator().identity()))
        observeMember(change.operator(), type, time, relation, true);
    }
    if (type.startsWith("GUILD_") && !type.startsWith("GUILD_MEMBER")) {
      String id = text(raw.get("id"));
      if (!id.isBlank())
        upsert(
            new Identity(e.bot(), EntityType.GUILD, "guild_id", id),
            raw,
            type,
            time,
            null,
            null,
            !type.equals("GUILD_DELETE"));
    }
    if (type.startsWith("CHANNEL_")) {
      String id = text(raw.get("id"));
      if (!id.isBlank())
        upsert(
            new Identity(e.bot(), EntityType.CHANNEL, "channel_id", id),
            raw,
            type,
            time,
            null,
            null,
            !type.equals("CHANNEL_DELETE"));
    }
  }

  public static String text(Object o) {
    return o == null ? "" : o.toString();
  }

  private void observeMember(
      PlatformMember member, String source, Instant time, ChatRef chat, boolean present) {
    if (member == null || member.identity() == null) return;
    var fields = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "username",
            "avatar",
            "bot",
            "member_role",
            "member_nickname",
            "nick",
            "roles",
            "joined_at")) if (member.raw().get(key) != null) fields.put(key, member.raw().get(key));
    upsert(member.identity(), fields, source, time, member.unionOpenid(), chat, present);
  }

  public static void validatePage(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 500)
      throw new IllegalArgumentException("offset >= 0 and limit 1..500 required");
  }

  private static <T> Page<T> page(List<T> rows, int offset, int limit) {
    return new Page<>(rows.stream().limit(limit).toList(), offset, limit, rows.size() > limit);
  }
}
