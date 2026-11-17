package top.hetaoawa.astra.storage;

import top.hetaoawa.astra.core.*;

public final class JdbcOutboundStore implements OutboundStore {
  private final JdbcDatabase db;
  private final java.util.function.BooleanSupplier available;

  public JdbcOutboundStore(JdbcDatabase db) {
    this(db, () -> true);
  }

  public JdbcOutboundStore(JdbcDatabase db, java.util.function.BooleanSupplier available) {
    this.db = db;
    this.available = available;
  }

  private void requireLease() {
    if (!available.getAsBoolean())
      throw new IllegalStateException("Runtime lease unavailable; request not started");
  }

  public synchronized void rememberReply(
      String chatKey, String messageId, long expiresAt, int maximum) {
    if (messageId == null || messageId.isBlank() || maximum < 1)
      throw new IllegalArgumentException("Message ID and positive reply limit required");
    if (expiresAt <= System.currentTimeMillis()) return;
    String key = top.hetaoawa.astra.api.Keys.join(chatKey, messageId);
    db.transaction.executeWithoutResult(
        tx -> {
          var existing =
              db.jdbc.query(
                  "SELECT expires_at FROM astra_reply_contexts WHERE reply_key=?",
                  (r, n) -> r.getLong(1),
                  key);
          if (existing.isEmpty())
            db.jdbc.update(
                "INSERT INTO"
                    + " astra_reply_contexts(reply_key,chat_key,message_id,next_seq,maximum,expires_at)"
                    + " VALUES(?,?,?,0,?,?)",
                key,
                chatKey,
                messageId,
                maximum,
                expiresAt);
          else if (existing.getFirst() > expiresAt)
            db.jdbc.update(
                "UPDATE astra_reply_contexts SET expires_at=? WHERE reply_key=?", expiresAt, key);
        });
  }

  public synchronized Reply claimReply(String chatKey, String messageId, long now) {
    requireLease();
    return db.transaction.execute(
        tx -> {
          var candidates =
              db.jdbc.query(
                  "SELECT reply_key,message_id,next_seq,expires_at FROM astra_reply_contexts"
                      + " WHERE chat_key=? AND expires_at>? AND next_seq<maximum"
                      + (messageId == null ? "" : " AND reply_key=?")
                      + " ORDER BY expires_at,reply_key LIMIT 1",
                  (r, n) ->
                      new Object[] {r.getString(1), r.getString(2), r.getInt(3), r.getLong(4)},
                  messageId == null
                      ? new Object[] {chatKey, now}
                      : new Object[] {
                        chatKey, now, top.hetaoawa.astra.api.Keys.join(chatKey, messageId)
                      });
          for (var row : candidates) {
            int sequence = (Integer) row[2] + 1;
            if (db.jdbc.update(
                    "UPDATE astra_reply_contexts SET next_seq=? WHERE reply_key=? AND next_seq=?"
                        + " AND expires_at>? AND next_seq<maximum",
                    sequence,
                    row[0],
                    row[2],
                    now)
                == 1) return new Reply((String) row[1], sequence, (Long) row[3]);
          }
          return null;
        });
  }

  public void reserve(String id, String bot, String owner) {
    db.jdbc.update(
        "INSERT INTO astra_outbound(job_id,bot_key,plugin_id,state,result_json,created_at)"
            + " VALUES(?,?,?,'PENDING','',?)",
        id,
        bot,
        owner,
        System.currentTimeMillis());
  }

  public boolean start(String id) {
    requireLease();
    return db.jdbc.update(
            "UPDATE astra_outbound SET state='STARTED' WHERE job_id=? AND state='PENDING'", id)
        == 1;
  }

  public void finish(String id, String state, String result) {
    db.jdbc.update(
        "UPDATE astra_outbound SET state=?,result_json=? WHERE job_id=? AND state"
            + " IN('STARTED','PENDING')",
        state,
        result,
        id);
  }

  public void cancel(String bot, String owner) {
    db.jdbc.update(
        "UPDATE astra_outbound SET state='CANCELLED' WHERE bot_key=? AND plugin_id=? AND"
            + " state='PENDING'",
        bot,
        owner);
  }

  public void recover(boolean durable) {
    db.transaction.executeWithoutResult(
        tx -> {
          var started =
              db.jdbc.query(
                  "SELECT job_id FROM astra_outbound WHERE state='STARTED'",
                  (r, n) -> r.getString(1));
          for (String id : started)
            db.jdbc.update(
                "UPDATE astra_history SET processing_state='OUTCOME_UNKNOWN' WHERE event_key=?",
                top.hetaoawa.astra.api.Keys.join("outbound", id));
          db.jdbc.update("UPDATE astra_outbound SET state='INTERRUPTED' WHERE state='STARTED'");
          if (!durable) {
            var pending =
                db.jdbc.query(
                    "SELECT job_id FROM astra_outbound WHERE state='PENDING'",
                    (r, n) -> r.getString(1));
            for (String id : pending)
              db.jdbc.update(
                  "UPDATE astra_history SET processing_state='LOST_IN_MEMORY' WHERE event_key=?",
                  top.hetaoawa.astra.api.Keys.join("outbound", id));
            db.jdbc.update("UPDATE astra_outbound SET state='INTERRUPTED' WHERE state='PENDING'");
          }
        });
  }

  public int nextReply(String key, int maximum) {
    return db.transaction.execute(
        tx -> {
          var value =
              db.jdbc.query(
                  "SELECT next_seq FROM astra_reply_sequences WHERE reply_key=?",
                  (r, n) -> r.getInt(1),
                  key);
          if (value.isEmpty()) {
            db.jdbc.update(
                "INSERT INTO astra_reply_sequences(reply_key,next_seq,expires_at) VALUES(?,1,?)",
                key,
                System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis());
            return 1;
          }
          int next = value.getFirst() + 1;
          if (next > maximum) throw new IllegalStateException("Passive reply quota exhausted");
          if (db.jdbc.update(
                  "UPDATE astra_reply_sequences SET next_seq=? WHERE reply_key=? AND next_seq=?",
                  next,
                  key,
                  next - 1)
              != 1)
            throw new IllegalStateException(
                "Concurrent reply sequence allocation; caller may submit a new request");
          return next;
        });
  }

  public void cleanup(long before) {
    db.jdbc.update(
        "DELETE FROM astra_reply_contexts WHERE expires_at<=?", System.currentTimeMillis());
    db.jdbc.update(
        "DELETE FROM astra_outbound WHERE created_at<? AND state<>'PENDING' AND state<>'STARTED'",
        before);
    db.jdbc.update(
        "DELETE FROM astra_reply_sequences WHERE expires_at<?", System.currentTimeMillis());
  }
}
