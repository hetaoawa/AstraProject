package top.hetaoawa.astra.storage;

import com.zaxxer.hikari.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

public final class JdbcDatabase implements AutoCloseable {
  private final HikariDataSource source;
  public final JdbcTemplate jdbc;
  public final TransactionTemplate transaction;
  public final String dialect;

  public JdbcDatabase(String dialect, String url, String user, String password) {
    this.dialect = dialect.toLowerCase(Locale.ROOT);
    if (!Set.of("sqlite", "postgresql", "mysql").contains(this.dialect))
      throw new IllegalArgumentException("Unsupported database");
    HikariConfig c = new HikariConfig();
    c.setJdbcUrl(url);
    if (user != null && !user.isBlank()) c.setUsername(user);
    if (password != null) c.setPassword(password);
    c.setMaximumPoolSize(this.dialect.equals("sqlite") ? 1 : 8);
    c.setPoolName("astra-store");
    c.setConnectionTimeout(10000);
    if (this.dialect.equals("sqlite")) c.setConnectionInitSql("PRAGMA busy_timeout=10000");
    source = new HikariDataSource(c);
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    if (this.dialect.equals("sqlite")) {
      jdbc.execute("PRAGMA journal_mode=WAL");
      jdbc.execute("PRAGMA synchronous=FULL");
      jdbc.execute("PRAGMA foreign_keys=ON");
    }
    migrate();
  }

  private void migrate() {
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS astra_schema_version(version INTEGER NOT NULL PRIMARY KEY)");
    var v = jdbc.query("SELECT version FROM astra_schema_version", (rs, n) -> rs.getInt(1));
    if (v.size() > 1 || !v.isEmpty() && (v.getFirst() < 1 || v.getFirst() > 5))
      throw new IllegalStateException("Unsupported Astra schema version");
    int current = v.isEmpty() ? 0 : v.getFirst();
    if (current == 0) {
      script("V1__framework.sql");
      jdbc.update("INSERT INTO astra_schema_version(version) VALUES(1)");
      current = 1;
    }
    if (current == 1) {
      script("V2__history_and_retention.sql");
      var rows =
          jdbc.query(
              "SELECT record_key,chat_key,message_index,event_key FROM astra_history WHERE"
                  + " message_index<>'' ORDER BY occurred_at,record_key",
              (r, n) ->
                  new String[] {r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
      for (var row : rows) {
        String key = top.hetaoawa.astra.api.Keys.join(row[1], row[2]);
        if (jdbc.update(
                "UPDATE astra_message_indexes SET record_key=?,observed_event=? WHERE alias_key=?",
                row[0],
                row[3],
                key)
            == 0)
          jdbc.update(
              "INSERT INTO"
                  + " astra_message_indexes(alias_key,chat_key,index_hash,index_value,record_key,observed_event,temporary_flag)"
                  + " VALUES(?,?,?,?,?,?,?)",
              key,
              row[1],
              top.hetaoawa.astra.api.Keys.join(row[2]),
              row[2],
              row[0],
              row[3],
              row[2].startsWith("TMP_") ? 1 : 0);
      }
      jdbc.update(
          "UPDATE astra_reply_sequences SET expires_at=? WHERE expires_at=0",
          System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis());
      jdbc.update(
          "UPDATE astra_events SET completed_at=? WHERE state<>'PENDING' AND completed_at IS NULL",
          System.currentTimeMillis());
      jdbc.update("UPDATE astra_schema_version SET version=2 WHERE version=1");
      current = 2;
    }
    if (current == 2) {
      script("V3__event_archive.sql");
      jdbc.update("UPDATE astra_schema_version SET version=3 WHERE version=2");
      current = 3;
    }
    if (current == 3) {
      script("V4__outbound_quotas.sql");
      jdbc.update("UPDATE astra_schema_version SET version=4 WHERE version=3");
      current = 4;
    }
    if (current == 4) {
      script("V5__passive_reply_contexts.sql");
      jdbc.update("UPDATE astra_schema_version SET version=5 WHERE version=4");
    }
  }

  private void script(String name) {
    try (InputStream in = getClass().getResourceAsStream("/db/" + dialect + "/" + name)) {
      if (in == null) throw new IllegalStateException("Schema not found");
      for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";"))
        if (!sql.isBlank()) {
          var alter =
              java.util.regex.Pattern.compile("(?is)^\\s*ALTER TABLE (\\w+) ADD COLUMN (\\w+).*?")
                  .matcher(sql);
          if (alter.matches() && columnExists(alter.group(1), alter.group(2))) continue;
          jdbc.execute(sql);
        }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean columnExists(String table, String column) {
    try (var connection = source.getConnection();
        var fields =
            connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
      return fields.next();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public void close() {
    source.close();
  }
}
