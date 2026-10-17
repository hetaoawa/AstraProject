package top.hetaoawa.astra.storage;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A dataset is owned by one framework process. Lease loss fences ingress and business execution.
 */
public final class JdbcRuntimeLease implements AutoCloseable {
  private final JdbcDatabase db;
  private final String owner = UUID.randomUUID().toString();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final AtomicBoolean valid = new AtomicBoolean(true);

  public JdbcRuntimeLease(JdbcDatabase db) {
    this.db = db;
    long now = System.currentTimeMillis();
    boolean acquired =
        db.transaction.execute(
            tx -> {
              var rows =
                  db.jdbc.query(
                      "SELECT owner_id,expires_at FROM astra_runtime_lock WHERE lock_id=1",
                      (r, n) -> r.getLong(2));
              if (rows.isEmpty()) {
                db.jdbc.update(
                    "INSERT INTO astra_runtime_lock(lock_id,owner_id,expires_at) VALUES(1,?,?)",
                    owner,
                    now + 30000);
                return true;
              }
              return db.jdbc.update(
                      "UPDATE astra_runtime_lock SET owner_id=?,expires_at=? WHERE lock_id=1 AND"
                          + " expires_at<?",
                      owner,
                      now + 30000,
                      now)
                  == 1;
            });
    if (!acquired)
      throw new IllegalStateException(
          "This Astra database is already owned by another runtime; use a separate database and"
              + " Redis namespace");
    timer.scheduleAtFixedRate(
        () -> {
          try {
            if (db.jdbc.update(
                    "UPDATE astra_runtime_lock SET expires_at=? WHERE lock_id=1 AND owner_id=? AND"
                        + " expires_at>?",
                    System.currentTimeMillis() + 30000,
                    owner,
                    System.currentTimeMillis())
                != 1) valid.set(false);
          } catch (Throwable e) {
            valid.set(false);
          }
        },
        5,
        5,
        TimeUnit.SECONDS);
  }

  public boolean valid() {
    return valid.get();
  }

  public void close() {
    valid.set(false);
    timer.shutdownNow();
    db.jdbc.update("DELETE FROM astra_runtime_lock WHERE lock_id=1 AND owner_id=?", owner);
  }
}
