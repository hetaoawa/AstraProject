package top.hetaoawa.astra.storage;

import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class JdbcEventArchive implements EventArchive {
  private final JdbcDatabase db;
  private final EventCodec codec;
  private final boolean enabled;

  public JdbcEventArchive(JdbcDatabase db, EventCodec codec, boolean enabled) {
    this.db = db;
    this.codec = codec;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  public void append(
      BotId bot,
      String transport,
      String packet,
      Map<String, Object> envelope,
      Event event,
      Instant received) {
    if (!enabled) return;
    Object op = envelope.get("op"), seq = envelope.get("s");
    int opcode = op instanceof Number n ? n.intValue() : -1;
    String type = envelope.get("t") instanceof String t && !t.isBlank() ? t : "OPCODE_" + opcode;
    db.jdbc.update(
        "INSERT INTO"
            + " astra_event_archive(receipt_id,bot_key,bot_json,transport,event_type,opcode,sequence_number,platform_event_id,received_at,raw_packet,event_json)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        bot.key(),
        JsonSupport.write(bot),
        transport,
        type,
        opcode,
        seq instanceof Number n ? n.longValue() : null,
        envelope.get("id") instanceof String id ? id : "",
        received.toEpochMilli(),
        packet,
        codec.encode(event));
  }

  public Page<EventArchive.Record> search(Query query) {
    if (!enabled) throw new EventArchiveDisabledException();
    JdbcDirectory.validatePage(query.offset(), query.limit());
    String sql = "SELECT * FROM astra_event_archive WHERE bot_key=?";
    var args = new ArrayList<Object>(List.of(query.bot().key()));
    if (!query.types().isEmpty()) {
      sql +=
          " AND event_type IN("
              + String.join(",", Collections.nCopies(query.types().size(), "?"))
              + ")";
      args.addAll(query.types());
    }
    if (query.from() != null) {
      sql += " AND received_at>=?";
      args.add(query.from().toEpochMilli());
    }
    if (query.to() != null) {
      sql += " AND received_at<=?";
      args.add(query.to().toEpochMilli());
    }
    sql += " ORDER BY received_at DESC,receipt_id DESC LIMIT ? OFFSET ?";
    args.add(query.limit() + 1);
    args.add(query.offset());
    var records =
        db.jdbc.query(
            sql,
            (r, n) ->
                new EventArchive.Record(
                    r.getString("receipt_id"),
                    JsonSupport.read(r.getString("bot_json"), BotId.class),
                    r.getString("transport"),
                    r.getString("event_type"),
                    r.getInt("opcode"),
                    r.getObject("sequence_number") == null ? null : r.getLong("sequence_number"),
                    r.getString("platform_event_id"),
                    Instant.ofEpochMilli(r.getLong("received_at")),
                    r.getString("raw_packet"),
                    codec.decode(r.getString("event_json"))),
            args.toArray());
    return new Page<>(
        records.stream().limit(query.limit()).toList(),
        query.offset(),
        query.limit(),
        records.size() > query.limit());
  }

  public EventArchive scoped(BotId bot) {
    return new EventArchive() {
      public boolean enabled() {
        return JdbcEventArchive.this.enabled;
      }

      public Page<EventArchive.Record> search(Query query) {
        if (!bot.equals(query.bot()))
          throw new IllegalArgumentException("Archive bot scope mismatch");
        return JdbcEventArchive.this.search(query);
      }
    };
  }

  public void cleanup(Duration retention) {
    if (!retention.isNegative() && !retention.isZero())
      db.jdbc.update(
          "DELETE FROM astra_event_archive WHERE received_at<?",
          Instant.now().minus(retention).toEpochMilli());
  }
}
