package top.hetaoawa.astra.storage;

import java.util.*;
import top.hetaoawa.astra.core.QuotaStore;

public final class JdbcQuotaStore implements QuotaStore {
  private final JdbcDatabase db;

  public JdbcQuotaStore(JdbcDatabase db) {
    this.db = db;
  }

  public synchronized Denied reserve(List<Charge> charges, long now) {
    if (charges.isEmpty()) return null;
    return db.transaction.execute(
        tx -> {
          List<Charge> changes = new ArrayList<>();
          for (var c : charges) {
            var rows =
                db.jdbc.query(
                    "SELECT used_count,expires_at FROM astra_quotas WHERE quota_key=?",
                    (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
                    c.key());
            if (!rows.isEmpty() && rows.getFirst()[1] <= now) {
              db.jdbc.update("DELETE FROM astra_quota_members WHERE quota_key=?", c.key());
              db.jdbc.update("DELETE FROM astra_quotas WHERE quota_key=?", c.key());
              rows = List.of();
            }
            boolean present =
                c.member() != null
                    && !db.jdbc
                        .query(
                            "SELECT member_key FROM astra_quota_members WHERE quota_key=? AND"
                                + " member_key=?",
                            (r, n) -> r.getString(1),
                            c.key(),
                            c.member())
                        .isEmpty();
            if (present) continue;
            long used = rows.isEmpty() ? 0 : rows.getFirst()[0];
            if (used >= c.limit())
              return new Denied(c.key(), rows.isEmpty() ? c.expiresAt() : rows.getFirst()[1]);
            changes.add(c);
          }
          for (var c : changes) {
            if (db.jdbc.update(
                    "UPDATE astra_quotas SET used_count=used_count+1 WHERE quota_key=?", c.key())
                == 0)
              db.jdbc.update(
                  "INSERT INTO astra_quotas(quota_key,used_count,expires_at) VALUES(?,1,?)",
                  c.key(),
                  c.expiresAt());
            if (c.member() != null)
              db.jdbc.update(
                  "INSERT INTO astra_quota_members(quota_key,member_key) VALUES(?,?)",
                  c.key(),
                  c.member());
          }
          return null;
        });
  }

  public synchronized Denied check(List<Charge> charges, long now) {
    for (var c : charges) {
      var rows =
          db.jdbc.query(
              "SELECT used_count,expires_at FROM astra_quotas WHERE quota_key=? AND expires_at>?",
              (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
              c.key(),
              now);
      if (c.limit() <= 0) return new Denied(c.key(), c.expiresAt());
      if (rows.isEmpty()) continue;
      if (c.member() != null
          && !db.jdbc
              .query(
                  "SELECT member_key FROM astra_quota_members WHERE quota_key=? AND member_key=?",
                  (r, n) -> r.getString(1),
                  c.key(),
                  c.member())
              .isEmpty()) continue;
      if (rows.getFirst()[0] >= c.limit()) return new Denied(c.key(), rows.getFirst()[1]);
    }
    return null;
  }

  public synchronized State load(String key, long now) {
    var rows =
        db.jdbc.query(
            "SELECT state_json,expires_at FROM astra_rate_state WHERE state_key=? AND expires_at>?",
            (r, n) -> new State(r.getString(1), r.getLong(2)),
            key,
            now);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public synchronized void save(String key, String value, long expiresAt) {
    if (db.jdbc.update(
            "UPDATE astra_rate_state SET state_json=?,expires_at=? WHERE state_key=?",
            value,
            expiresAt,
            key)
        == 0)
      db.jdbc.update(
          "INSERT INTO astra_rate_state(state_key,state_json,expires_at) VALUES(?,?,?)",
          key,
          value,
          expiresAt);
  }

  public synchronized void cleanup(long now) {
    db.transaction.executeWithoutResult(
        tx -> {
          db.jdbc.update(
              "DELETE FROM astra_quota_members WHERE quota_key IN (SELECT quota_key FROM"
                  + " astra_quotas WHERE expires_at<=?)",
              now);
          db.jdbc.update("DELETE FROM astra_quotas WHERE expires_at<=?", now);
          db.jdbc.update("DELETE FROM astra_rate_state WHERE expires_at<=?", now);
        });
  }
}
