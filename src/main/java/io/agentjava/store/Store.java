package io.agentjava.store;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.config.ProjectConfig;
import java.nio.file.Files;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;

@Component
public class Store {
  public final JdbcTemplate db;

  public Store(ProjectConfig config) {
    try {
      Files.createDirectories(config.database().getParent());
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl("jdbc:sqlite:" + config.database());
      ds.getConfig().setBusyTimeout(10000);
      ds.getConfig().enforceForeignKeys(true);
      db = new JdbcTemplate(ds);
      db.execute("PRAGMA journal_mode=WAL");
      db.execute(
          "CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at"
              + " TEXT NOT NULL)");
      var tx =
          new org.springframework.transaction.support.TransactionTemplate(
              new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
      for (int i = 1; i <= 7; i++) {
        if (db.queryForObject(
                "SELECT count(*) FROM schema_migrations WHERE version=?", Integer.class, i)
            == 0) {
          var res = Store.class.getResourceAsStream("/db/migration/V" + i + ".sql");
          String sql =
              new String(
                  Objects.requireNonNull(res).readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          final int version = i;
          tx.executeWithoutResult(
              status -> {
                for (String statement : sql.split(";"))
                  if (!statement.isBlank()) db.execute(statement);
                db.update("INSERT INTO schema_migrations VALUES(?,?)", version, now());
              });
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("SQLite 初始化失败", e);
    }
  }

  public synchronized Map<String, Object> put(
      String kind, String owner, String parent, Map<String, Object> value) {
    if (owner.isBlank() || s(value, "id").isBlank())
      throw new IllegalArgumentException("scope/id 缺失");
    value.put("ownerUserId", owner);
    String stamp = now();
    int changed =
        db.update(
            "INSERT INTO resources(kind,id,owner,parent,data,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET"
                + " data=excluded.data,updated_at=excluded.updated_at WHERE"
                + " resources.owner=excluded.owner",
            kind,
            s(value, "id"),
            owner,
            parent,
            encode(value),
            stamp,
            stamp);
    if (changed != 1) throw new ApiException("AUTH_FORBIDDEN");
    return value;
  }

  public Map<String, Object> get(String kind, String owner, String id) {
    var rows =
        db.queryForList(
            "SELECT data FROM resources WHERE kind=? AND owner=? AND id=?", kind, owner, id);
    if (rows.isEmpty()) throw new ApiException("AUTH_FORBIDDEN");
    return decode((String) rows.get(0).get("data"));
  }

  public List<Map<String, Object>> list(String kind, String owner, String parent) {
    var rows =
        parent == null
            ? db.queryForList(
                "SELECT data FROM resources WHERE kind=? AND owner=? ORDER BY updated_at DESC,id",
                kind,
                owner)
            : db.queryForList(
                "SELECT data FROM resources WHERE kind=? AND owner=? AND parent=? ORDER BY"
                    + " created_at,id",
                kind,
                owner,
                parent);
    return rows.stream().map(r -> decode((String) r.get("data"))).toList();
  }

  public void delete(String kind, String owner, String id) {
    get(kind, owner, id);
    db.update("DELETE FROM resources WHERE kind=? AND owner=? AND id=?", kind, owner, id);
  }

  public void children(String owner, String parent) {
    db.update("DELETE FROM resources WHERE owner=? AND parent=?", owner, parent);
  }
}
