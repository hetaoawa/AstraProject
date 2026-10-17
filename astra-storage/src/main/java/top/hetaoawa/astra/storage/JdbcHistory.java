package top.hetaoawa.astra.storage;

import java.time.*;
import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class JdbcHistory implements History, ObservationStore {
  private final JdbcDatabase db;
  private final boolean enabled;

  public JdbcHistory(JdbcDatabase db, boolean enabled) {
    this.db = db;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  private void requireEnabled() {
    if (!enabled) throw new HistoryDisabledException();
  }

  private MessageRecord record(java.sql.ResultSet r) throws java.sql.SQLException {
    return new MessageRecord(
        JsonSupport.read(r.getString("chat_json"), ChatRef.class),
        r.getString("actor_json") == null
            ? null
            : JsonSupport.read(r.getString("actor_json"), Identity.class),
        JsonSupport.read(r.getString("message_json"), Message.class),
        Instant.ofEpochMilli(r.getLong("occurred_at")),
        r.getInt("outgoing_flag") != 0,
        r.getInt("recalled_flag") != 0,
        r.getObject("recalled_at") == null ? null : Instant.ofEpochMilli(r.getLong("recalled_at")),
        r.getString("processing_state"),
        r.getString("record_key"),
        sources(r));
  }

  private Set<RecallSource> sources(java.sql.ResultSet r) throws java.sql.SQLException {
    var sources = EnumSet.noneOf(RecallSource.class);
    if (r.getInt("recall_api_flag") != 0) sources.add(RecallSource.API_SUCCESS);
    if (r.getInt("recall_notice_flag") != 0) sources.add(RecallSource.PLATFORM_NOTICE);
    return sources;
  }

  public Optional<MessageRecord> findByIndex(ChatRef chat, String index) {
    requireEnabled();
    if (index == null || index.isBlank())
      throw new IllegalArgumentException("Nonempty message index required");
    return db
        .jdbc
        .query(
            "SELECT h.* FROM astra_history h JOIN astra_message_indexes i ON"
                + " h.record_key=i.record_key WHERE i.chat_key=? AND i.index_hash=? ORDER BY"
                + " h.occurred_at DESC,h.record_key LIMIT 1",
            (r, n) -> record(r),
            chat.key(),
            Keys.join(index))
        .stream()
        .findFirst();
  }

  public Optional<Context> context(ContextQuery q) {
    requireEnabled();
    var anchor =
        q.messageId() == null
            ? findByIndex(q.chat(), q.index())
            : find(q.chat(), q.messageId(), q.index());
    return anchor.map(a -> new Context(a, neighbors(q, a, true), neighbors(q, a, false)));
  }

  private List<MessageRecord> neighbors(ContextQuery q, MessageRecord anchor, boolean before) {
    int count = before ? q.before() : q.after();
    if (count == 0) return List.of();
    String op = before ? "<" : ">", order = before ? "DESC" : "ASC";
    String sql =
        "SELECT * FROM astra_history WHERE chat_key=? AND (occurred_at"
            + op
            + "? OR (occurred_at=? AND record_key"
            + op
            + "?))";
    var args =
        new ArrayList<Object>(
            List.of(
                q.chat().key(),
                anchor.occurredAt().toEpochMilli(),
                anchor.occurredAt().toEpochMilli(),
                anchor.recordKey()));
    if (q.actor() != null) {
      sql += " AND actor_key=?";
      args.add(q.actor().key());
    }
    if (q.direction() != Direction.ANY) {
      sql += " AND outgoing_flag=?";
      args.add(q.direction() == Direction.OUTBOUND ? 1 : 0);
    }
    sql += " ORDER BY occurred_at " + order + ",record_key " + order + " LIMIT ?";
    args.add(count);
    var rows = new ArrayList<>(db.jdbc.query(sql, (r, n) -> record(r), args.toArray()));
    if (before) Collections.reverse(rows);
    return List.copyOf(rows);
  }

  public Optional<MessageRecord> find(ChatRef chat, String id, String index) {
    requireEnabled();
    if (index == null)
      return db
          .jdbc
          .query(
              "SELECT * FROM astra_history WHERE chat_key=? AND message_id=? ORDER BY occurred_at"
                  + " DESC,record_key LIMIT 1",
              (r, n) -> record(r),
              chat.key(),
              id)
          .stream()
          .findFirst();
    if (index.isBlank())
      return db
          .jdbc
          .query(
              "SELECT * FROM astra_history WHERE chat_key=? AND message_id=? AND message_index=''",
              (r, n) -> record(r),
              chat.key(),
              id)
          .stream()
          .findFirst();
    return findByIndex(chat, index).filter(r -> r.message().id().equals(id));
  }

  public Page<MessageRecord> search(Query q) {
    requireEnabled();
    JdbcDirectory.validatePage(q.offset(), q.limit());
    String sql = "SELECT * FROM astra_history WHERE bot_key=?";
    var args = new ArrayList<Object>(List.of(q.bot().key()));
    if (q.chat() != null) {
      if (!q.chat().bot().equals(q.bot())) throw new IllegalArgumentException("Chat bot mismatch");
      sql += " AND chat_key=?";
      args.add(q.chat().key());
    }
    if (q.actor() != null) {
      if (!q.actor().bot().equals(q.bot()))
        throw new IllegalArgumentException("Actor bot mismatch");
      sql += " AND actor_key=?";
      args.add(q.actor().key());
    }
    if (q.from() != null) {
      sql += " AND occurred_at>=?";
      args.add(q.from().toEpochMilli());
    }
    if (q.to() != null) {
      sql += " AND occurred_at<=?";
      args.add(q.to().toEpochMilli());
    }
    if (q.recalled() != null) {
      sql += " AND recalled_flag=?";
      args.add(q.recalled() ? 1 : 0);
    }
    if (q.direction() != Direction.ANY) {
      sql += " AND outgoing_flag=?";
      args.add(q.direction() == Direction.OUTBOUND ? 1 : 0);
    }
    sql += " ORDER BY occurred_at DESC,record_key LIMIT ? OFFSET ?";
    args.add(q.limit() + 1);
    args.add(q.offset());
    var rows = db.jdbc.query(sql, (r, n) -> record(r), args.toArray());
    return new Page<>(
        rows.stream().limit(q.limit()).toList(), q.offset(), q.limit(), rows.size() > q.limit());
  }

  public void observe(Event e) {
    if (!enabled) return;
    if (e instanceof MessageEvent m) save(m, false);
    else if (e instanceof MessageRecallEvent r
        && (e.chat().scene() == Scene.CHANNEL || e.chat().scene() == Scene.CHANNEL_DM))
      markRecall(r);
  }

  public void save(MessageEvent e, boolean outgoing) {
    if (!enabled) return;
    var m = e.message();
    db.transaction.executeWithoutResult(
        tx -> {
          String key =
              db
                  .jdbc
                  .query(
                      "SELECT record_key FROM astra_history WHERE chat_key=? AND message_id=? ORDER"
                          + " BY occurred_at DESC,record_key LIMIT 1",
                      (r, n) -> r.getString(1),
                      e.chat().key(),
                      m.id())
                  .stream()
                  .findFirst()
                  .orElse(Keys.join(e.chat().key(), m.id(), m.index()));
          var recalls =
              db.jdbc.query(
                  "SELECT recalled_at FROM astra_history WHERE chat_key=? AND message_id=? AND"
                      + " recalled_flag=1",
                  (r, n) -> r.getLong(1),
                  e.chat().key(),
                  m.id());
          Long recalled = recalls.isEmpty() ? null : recalls.getFirst();
          var existing =
              db.jdbc.query(
                  "SELECT record_key FROM astra_history WHERE record_key=?",
                  (r, n) -> r.getString(1),
                  key);
          if (existing.isEmpty())
            db.jdbc.update(
                "INSERT INTO"
                    + " astra_history(record_key,event_key,bot_key,chat_key,chat_json,actor_key,actor_json,message_id,message_index,message_json,occurred_at,outgoing_flag,recalled_flag,recalled_at,processing_state)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                key,
                e.key(),
                e.bot().key(),
                e.chat().key(),
                JsonSupport.write(e.chat()),
                e.actor() == null ? null : e.actor().key(),
                e.actor() == null ? null : JsonSupport.write(e.actor()),
                m.id(),
                m.index(),
                JsonSupport.write(m),
                e.data().occurredAt().toEpochMilli(),
                outgoing ? 1 : 0,
                recalled == null ? 0 : 1,
                recalled,
                outgoing ? "SENT" : "RECEIVED");
          else
            db.jdbc.update(
                "UPDATE astra_history SET"
                    + " message_json=?,message_index=?,actor_key=?,actor_json=?,occurred_at=?,event_key=?,outgoing_flag=CASE"
                    + " WHEN outgoing_flag=1 OR ?=1 THEN 1 ELSE 0 END,processing_state=CASE WHEN"
                    + " processing_state='RECALL_PLACEHOLDER' THEN ? ELSE processing_state END"
                    + " WHERE record_key=?",
                JsonSupport.write(m),
                m.index(),
                e.actor() == null ? null : e.actor().key(),
                e.actor() == null ? null : JsonSupport.write(e.actor()),
                e.data().occurredAt().toEpochMilli(),
                e.key(),
                outgoing ? 1 : 0,
                outgoing ? "SENT" : "RECEIVED",
                key);
          if (!m.index().isBlank())
            db.jdbc.update(
                "UPDATE astra_message_indexes SET record_key=? WHERE record_key IN(SELECT"
                    + " record_key FROM astra_history WHERE chat_key=? AND message_id=? AND"
                    + " message_index='' AND processing_state='RECALL_PLACEHOLDER')",
                key,
                e.chat().key(),
                m.id());
          if (!m.index().isBlank()) index(e.chat(), m.index(), key, e.key());
          var priorSources =
              db.jdbc.query(
                  "SELECT recall_api_flag,recall_notice_flag FROM astra_history WHERE chat_key=?"
                      + " AND message_id=? AND recalled_flag=1",
                  (r, n) -> new int[] {r.getInt(1), r.getInt(2)},
                  e.chat().key(),
                  m.id());
          if (!priorSources.isEmpty())
            db.jdbc.update(
                "UPDATE astra_history SET recall_api_flag=?,recall_notice_flag=? WHERE"
                    + " record_key=?",
                priorSources.stream().anyMatch(a -> a[0] != 0) ? 1 : 0,
                priorSources.stream().anyMatch(a -> a[1] != 0) ? 1 : 0,
                key);
          // Existing placeholders are filled in place, keeping every observed index alias.
        });
  }

  private void markRecall(MessageRecallEvent e) {
    markRecall(e.chat(), e.messageId(), e.messageIndex(), e.data().occurredAt(), e.key(), false);
  }

  public void recallSucceeded(ChatRef chat, String messageId, Instant time, String eventKey) {
    if (enabled) markRecall(chat, messageId, "", time, eventKey, true);
  }

  private void index(ChatRef chat, String value, String recordKey, String eventKey) {
    String key = Keys.join(chat.key(), value);
    if (db.jdbc.update(
            "UPDATE astra_message_indexes SET record_key=?,observed_event=? WHERE alias_key=?",
            recordKey,
            eventKey,
            key)
        == 0)
      db.jdbc.update(
          "INSERT INTO"
              + " astra_message_indexes(alias_key,chat_key,index_hash,index_value,record_key,observed_event,temporary_flag)"
              + " VALUES(?,?,?,?,?,?,?)",
          key,
          chat.key(),
          Keys.join(value),
          value,
          recordKey,
          eventKey,
          value.startsWith("TMP_") ? 1 : 0);
  }

  private void markRecall(
      ChatRef chat,
      String messageId,
      String messageIndex,
      Instant time,
      String eventKey,
      boolean api) {
    db.transaction.executeWithoutResult(
        tx -> {
          int count =
              db.jdbc.update(
                  "UPDATE astra_history SET recalled_flag=1,"
                      + (api ? "recall_api_flag" : "recall_notice_flag")
                      + "=1,recalled_at=COALESCE(recalled_at,?)"
                      + " WHERE chat_key=? AND message_id=?",
                  time.toEpochMilli(),
                  chat.key(),
                  messageId);
          if (count == 0) {
            String index = messageIndex == null ? "" : messageIndex;
            var m = new Message(messageId, index, "", List.of(), false, Map.of());
            db.jdbc.update(
                "INSERT INTO"
                    + " astra_history(record_key,event_key,bot_key,chat_key,chat_json,actor_key,actor_json,message_id,message_index,message_json,occurred_at,outgoing_flag,recalled_flag,recalled_at,processing_state)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,0,1,?,'RECALL_PLACEHOLDER')",
                Keys.join(chat.key(), messageId, index),
                eventKey,
                chat.bot().key(),
                chat.key(),
                JsonSupport.write(chat),
                null,
                null,
                messageId,
                index,
                JsonSupport.write(m),
                time.toEpochMilli(),
                time.toEpochMilli());
            db.jdbc.update(
                "UPDATE astra_history SET "
                    + (api ? "recall_api_flag" : "recall_notice_flag")
                    + "=1 WHERE chat_key=? AND message_id=?",
                chat.key(),
                messageId);
            if (!index.isBlank())
              index(chat, index, Keys.join(chat.key(), messageId, index), eventKey);
          }
        });
  }

  public void saveStream(MessageEvent event, String mode) {
    if (!enabled) return;
    db.transaction.executeWithoutResult(
        tx -> {
          var previous = find(event.chat(), event.message().id(), null);
          String key =
              previous
                  .map(MessageRecord::recordKey)
                  .orElse(
                      Keys.join(event.chat().key(), event.message().id(), event.message().index()));
          var latest =
              db.jdbc.query(
                  "SELECT event_key FROM astra_history WHERE record_key=?",
                  (r, n) -> r.getString(1),
                  key);
          if (!latest.isEmpty() && latest.getFirst().equals(event.key())) return;
          if (mode.equals("append") && previous.isPresent()) {
            var before = previous.get().message();
            var segments = new ArrayList<>(before.segments());
            segments.addAll(event.message().segments());
            var merged =
                new Message(
                    event.message().id(),
                    event.message().index(),
                    before.text() + event.message().text(),
                    segments,
                    false,
                    event.message().raw());
            save(new MessageEvent(event.data(), merged), true);
          } else save(event, true);
        });
  }

  public void status(String eventKey, String state) {
    if (enabled)
      db.jdbc.update(
          "UPDATE astra_history SET processing_state=? WHERE event_key=?", state, eventKey);
  }

  public void removeAttempt(String eventKey, String id) {
    if (enabled)
      db.jdbc.update(
          "DELETE FROM astra_history WHERE event_key=? AND message_id=? AND outgoing_flag=1",
          eventKey,
          id);
  }

  public void cleanup(Duration retention) {
    if (enabled && !retention.isNegative() && !retention.isZero())
      db.jdbc.update(
          "DELETE FROM astra_history WHERE occurred_at<?",
          Instant.now().minus(retention).toEpochMilli());
    if (enabled)
      db.jdbc.update(
          "DELETE FROM astra_message_indexes WHERE record_key NOT IN(SELECT record_key FROM"
              + " astra_history)");
  }
}
