package top.hetaoawa.astra.storage;

import java.util.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class JdbcLedger implements ProcessingStore {
  private final JdbcDatabase db;

  public JdbcLedger(JdbcDatabase db) {
    this.db = db;
  }

  public boolean blocked(String key) {
    return !db.jdbc
        .query(
            "SELECT handler_key FROM astra_handlers WHERE event_key=? AND state='SUCCESS_BLOCK'",
            (rs, n) -> rs.getString(1),
            key)
        .isEmpty();
  }

  public boolean completed(String key) {
    return !db.jdbc
        .query(
            "SELECT state FROM astra_events WHERE event_key=? AND state<>'PENDING'",
            (rs, n) -> rs.getString(1),
            key)
        .isEmpty();
  }

  public List<Plan> prepare(Event e, List<Plan> plans) {
    return db.transaction.execute(
        tx -> {
          var existing =
              db.jdbc.query(
                  "SELECT state FROM astra_events WHERE event_key=?",
                  (rs, n) -> rs.getString(1),
                  e.key());
          if (existing.isEmpty()) {
            db.jdbc.update(
                "INSERT INTO astra_events(event_key,chat_key,state,created_at)"
                    + " VALUES(?,?,'PENDING',?)",
                e.key(),
                e.chat().key(),
                System.currentTimeMillis());
            int p = 0;
            for (var h : plans)
              db.jdbc.update(
                  "INSERT INTO"
                      + " astra_handlers(event_key,handler_key,plugin_id,position,state,error_text)"
                      + " VALUES(?,?,?,?,'PENDING','')",
                  e.key(),
                  h.handlerId(),
                  h.pluginId(),
                  p++);
          }
          return db.jdbc.query(
              "SELECT plugin_id,handler_key FROM astra_handlers WHERE event_key=? ORDER BY"
                  + " position",
              (rs, n) -> new Plan(rs.getString(1), rs.getString(2)),
              e.key());
        });
  }

  public boolean start(String e, String h) {
    return db.jdbc.update(
            "UPDATE astra_handlers SET state='STARTED' WHERE event_key=? AND handler_key=? AND"
                + " state='PENDING'",
            e,
            h)
        == 1;
  }

  public void finishHandler(String e, String h, String state, String error) {
    db.jdbc.update(
        "UPDATE astra_handlers SET state=?,error_text=? WHERE event_key=? AND handler_key=? AND"
            + " state='STARTED'",
        state,
        error,
        e,
        h);
  }

  public void finishEvent(Event e, String state) {
    db.transaction.executeWithoutResult(
        tx -> {
          db.jdbc.update(
              "UPDATE astra_events SET state=?,completed_at=? WHERE event_key=?",
              state,
              System.currentTimeMillis(),
              e.key());
          db.jdbc.update(
              "UPDATE astra_history SET processing_state=? WHERE event_key=?", state, e.key());
        });
  }

  public void recoverInterrupted() {
    db.jdbc.update(
        "UPDATE astra_handlers SET state='INTERRUPTED',error_text='Invocation interrupted by"
            + " runtime shutdown; not retried' WHERE state='STARTED'");
  }

  public void cleanup(long before) {
    db.transaction.executeWithoutResult(
        tx -> {
          db.jdbc.update(
              "DELETE FROM astra_handlers WHERE event_key IN(SELECT event_key FROM astra_events"
                  + " WHERE completed_at<? AND state<>'PENDING')",
              before);
          db.jdbc.update(
              "DELETE FROM astra_events WHERE completed_at<? AND state<>'PENDING'", before);
        });
  }
}
