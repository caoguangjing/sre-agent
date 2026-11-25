package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.auth.AuthService;
import io.agentjava.store.Store;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SunfireAlertStore {
  static final Map<String, Long> TTL_HOURS = Map.of("warning", 2L, "critical", 4L, "urgent", 6L);
  private final JdbcTemplate db;

  public SunfireAlertStore(Store store) {
    this.db = store.db;
  }

  public Map<String, Object> save(Map<String, Object> payload) {
    if (payload.isEmpty()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    String alarmId = s(payload, "alarmId");
    if (alarmId.isBlank()) alarmId = AuthService.digest(encode(payload)).substring(0, 24);
    String alertName = firstNonBlank(payload, "ruleName", "rule", "title");
    String summary = firstNonBlank(payload, "content", "body");
    if (alertName.isBlank() && summary.isBlank())
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    String level = s(payload, "level").toLowerCase(Locale.ROOT);
    if (!TTL_HOURS.containsKey(level)) level = "warning";
    String service = firstNonBlank(payload, "appName", "app", "service");
    var labels = obj("alertname", alertName, "service", service, "severity", level);
    for (var t : seriesTags(payload))
      if (!s(t, "name").isBlank()) labels.putIfAbsent(s(t, "name"), s(t, "value"));
    var annotations = new LinkedHashMap<String, Object>();
    for (String k : List.of("ruleLink", "detailUrl"))
      if (!s(payload, k).isBlank()) annotations.put(k, s(payload, k));
    Map<String, Object> alert =
        obj(
            "id",
            alarmId,
            "source",
            "sunfire",
            "alertName",
            alertName,
            "service",
            service,
            "severity",
            level,
            "status",
            "firing",
            "startsAt",
            Instant.ofEpochMilli(parseTime(payload.get("alarmTime"))).toString(),
            "summary",
            summary,
            "labels",
            labels,
            "annotations",
            annotations,
            "context",
            payload);
    Instant expires = Instant.now().plus(TTL_HOURS.get(level), ChronoUnit.HOURS);
    db.update(
        "INSERT INTO sunfire_alerts(alarm_id,data,received_at,expires_at) VALUES(?,?,?,?)"
            + " ON CONFLICT(alarm_id) DO UPDATE SET data=excluded.data,"
            + " received_at=excluded.received_at,expires_at=excluded.expires_at",
        alarmId,
        encode(alert),
        now(),
        expires.toString());
    purge();
    return alert;
  }

  public List<Map<String, Object>> active() {
    purge();
    return db.queryForList("SELECT data FROM sunfire_alerts WHERE expires_at > ?", now()).stream()
        .map(r -> decode((String) r.get("data")))
        .toList();
  }

  private void purge() {
    db.update("DELETE FROM sunfire_alerts WHERE expires_at <= ?", now());
  }

  private static String firstNonBlank(Map<String, Object> payload, String... keys) {
    for (String key : keys) if (!s(payload, key).isBlank()) return s(payload, key);
    return "";
  }

  private static long parseTime(Object value) {
    String raw = str(value).trim();
    if (raw.matches("\\d{13}")) return Long.parseLong(raw);
    if (raw.matches("\\d{10}")) return Long.parseLong(raw) * 1000;
    try {
      return Instant.parse(raw).toEpochMilli();
    } catch (Exception e) {
      return System.currentTimeMillis();
    }
  }

  private static List<Map<String, Object>> seriesTags(Map<String, Object> payload) {
    Object series = payload.get("timeSeriesContexts");
    if (series instanceof String text) {
      try {
        series = text.isBlank() ? List.of() : decode(text);
      } catch (Exception e) {
        return List.of();
      }
    }
    var lines = maps(series);
    if (lines.isEmpty()) return List.of();
    var tags = map(lines.get(0)).get("tags");
    var entries = maps(map(tags).get("tags"));
    return entries.isEmpty() ? maps(tags) : entries;
  }
}
