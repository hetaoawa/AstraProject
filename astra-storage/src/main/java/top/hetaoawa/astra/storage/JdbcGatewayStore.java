package top.hetaoawa.astra.storage;

import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class JdbcGatewayStore implements GatewayStore {
  private final JdbcDatabase db;

  public JdbcGatewayStore(JdbcDatabase db) {
    this.db = db;
  }

  public Position load(BotId bot, int shard) {
    return db
        .jdbc
        .query(
            "SELECT session_id,accepted_seq FROM astra_gateway WHERE gateway_key=?",
            (r, n) -> new Position(r.getString(1), r.getLong(2)),
            Keys.join(bot.key(), String.valueOf(shard)))
        .stream()
        .findFirst()
        .orElse(new Position("", -1));
  }

  public void save(BotId bot, int shard, Position value) {
    db.transaction.executeWithoutResult(
        tx -> {
          String key = Keys.join(bot.key(), String.valueOf(shard));
          if (db.jdbc.update(
                  "UPDATE astra_gateway SET session_id=?,accepted_seq=? WHERE gateway_key=?",
                  value.session(),
                  value.sequence(),
                  key)
              == 0)
            db.jdbc.update(
                "INSERT INTO astra_gateway(gateway_key,session_id,accepted_seq) VALUES(?,?,?)",
                key,
                value.session(),
                value.sequence());
        });
  }
}
