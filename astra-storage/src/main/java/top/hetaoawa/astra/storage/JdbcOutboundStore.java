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
        "DELETE FROM astra_outbound WHERE created_at<? AND state<>'PENDING' AND state<>'STARTED'",
        before);
    db.jdbc.update(
        "DELETE FROM astra_reply_sequences WHERE expires_at<?", System.currentTimeMillis());
  }
}
