package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentjava.api.ApiException;
import io.agentjava.config.ProjectConfig;
import io.agentjava.mcp.McpService;
import io.agentjava.model.QwenProvider;
import io.agentjava.store.Store;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 智能巡检：定时扫描应用核心指标，环比突变检测发现异常，确认后自动起诊断。
 * 开关为部署级全局设置（inspection_settings 单行表），应用与阈值来自 config inspection 段；
 * 设置携带巡检指标清单（null=全部白名单指标，空=全不检）。检测指标来自策展白名单
 * （inspection/whitelist.json）：扫描轮对「勾选指标中尚未注册的条目」自动探针
 * （近 1 小时数据 + P50/P95）并经一次 LLM 批量调用设定检测规则（upGate/downGate/thresholdPercent）
 * 后注册，已注册条目跳过（幂等）。扫描按指标名过滤注册清单，判定全程零 LLM。
 */
@Service
public class InspectionService {
  private static final Logger log = LoggerFactory.getLogger(InspectionService.class);
  static final String QUERY_TOOL = "queryMetricsByPromQL";
  static final DateTimeFormatter BEIJING =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

  // upGate=up 方向当前值须达到的水位，downGate=down 方向当前值须低于的水位；null=该方向不过滤。
  // thresholdPercent=该指标自身的相对突变线（%），null=回落全局 thresholdPercent；
  // category=白名单类别（展示分组用，过滤按指标名进行）
  record Metric(
      String key, String name, String promql, String unit, Double upGate, Double downGate,
      Double thresholdPercent, String category) {}

  private final Store store;
  private final DiagnosisService diagnosis;
  private final McpService mcp;
  private final AlertService alerts;
  private final QwenProvider model;
  private final ProjectConfig config;
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<Map<String, Object>> whitelist = loadWhitelist();
  private final JdbcTemplate db;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "inspection-scan");
            t.setDaemon(true);
            return t;
          });

  public InspectionService(
      Store store, DiagnosisService diagnosis, McpService mcp, AlertService alerts,
      QwenProvider model, ProjectConfig config) {
    this.store = store;
    this.diagnosis = diagnosis;
    this.mcp = mcp;
    this.alerts = alerts;
    this.model = model;
    this.config = config;
    this.db = store.db;
    scheduler.scheduleWithFixedDelay(this::tick, 15, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  public void stop() {
    scheduler.shutdownNow();
  }

  public Map<String, Object> settings() {
    boolean enabled = false;
    String updatedAt = "";
    String metricsRaw = null;
    var rows = db.queryForList("SELECT enabled,updated_at,metrics FROM inspection_settings WHERE id=1");
    if (!rows.isEmpty()) {
      enabled = ((Number) rows.get(0).get("enabled")).intValue() == 1;
      updatedAt = str(rows.get(0).get("updated_at"));
      metricsRaw = str(rows.get(0).get("metrics"));
      // V5 seed 用 1970 占位（列 NOT NULL），未操作过开关时对外表现为未设置
      if (updatedAt.equals("1970-01-01T00:00:00Z")) updatedAt = "";
    }
    var section = map(config.section("inspection"));
    var ownerId = str(section.get("ownerId")).trim();
    var apps = apps(section);
    List<String> metrics = parseSelection(metricsRaw);
    return obj(
        "enabled",
        enabled,
        "updatedAt",
        updatedAt,
        "metrics",
        metrics,
        "availableCategories",
        whitelist.stream().map(e -> s(e, "category")).distinct().toList(),
        "catalog",
        whitelist.stream()
            .map(e -> obj("category", s(e, "category"), "name", s(e, "name"), "displayName", s(e, "displayName")))
            .toList(),
        "config",
        obj(
            "configured",
            !ownerId.isBlank() && !apps.isEmpty(),
            "apps",
            apps,
            "intervalMinutes",
            numberOr(section, "intervalMinutes", 5, 1),
            "windowMinutes",
            numberOr(section, "windowMinutes", 5, 1),
            "thresholdPercent",
            numberOr(section, "thresholdPercent", 30, 1),
            "autoDiagnose",
            !Boolean.FALSE.equals(section.get("autoDiagnose"))));
  }

  private List<String> parseSelection(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      List<?> list = mapper.readValue(raw, List.class);
      // [] 表示全不检（显式覆盖 null=全部），必须保留空数组语义
      return list.stream().map(v -> str(v).trim()).filter(v -> !v.isBlank()).toList();
    } catch (Exception e) {
      return null;
    }
  }

  /** 勾选指标集合；null=全部白名单指标。 */
  private Set<String> selectedMetrics() {
    var rows = db.queryForList("SELECT metrics FROM inspection_settings WHERE id=1");
    if (rows.isEmpty()) return null;
    var parsed = parseSelection(str(rows.get(0).get("metrics")));
    return parsed == null ? null : new HashSet<>(parsed);
  }

  public synchronized Map<String, Object> updateSettings(Map<String, Object> req) {
    if (!(req.get("enabled") instanceof Boolean)) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    boolean enabled = Boolean.TRUE.equals(req.get("enabled"));
    if (req.containsKey("metrics")) {
      Object raw = req.get("metrics");
      List<String> metrics;
      if (raw == null) {
        metrics = null;
      } else if (raw instanceof List<?> list) {
        // 空数组=全不检，保留显式空列表
        metrics = list.stream().map(v -> str(v).trim()).filter(v -> !v.isBlank()).distinct().toList();
      } else {
        throw new ApiException("VALIDATION_INVALID_ARGUMENT");
      }
      db.update(
          "UPDATE inspection_settings SET enabled=?, updated_at=?, metrics=? WHERE id=1",
          enabled ? 1 : 0,
          now(),
          metrics == null ? null : encode(metrics));
      recoverExcluded();
    } else {
      db.update(
          "UPDATE inspection_settings SET enabled=?, updated_at=? WHERE id=1",
          enabled ? 1 : 0,
          now());
    }
    return settings();
  }

  /** 勾选收窄后，不再勾选的指标的 open 发现不会被扫描，强制置为已恢复。 */
  private void recoverExcluded() {
    Set<String> selected = selectedMetrics();
    if (selected == null) return;
    var section = map(config.section("inspection"));
    var ownerId = ownerId(section);
    if (ownerId.isBlank()) return;
    for (var f : store.list("inspection_finding", ownerId, null)) {
      if (!s(f, "status").equals("open")) continue;
      if (!selected.contains(s(f, "metricKey"))) {
        f.put("status", "recovered");
        f.put("recoveredAt", now());
        store.put("inspection_finding", ownerId, "", f);
      }
    }
  }

  /**
   * 白名单条目探针：逐条验证（近 1 小时数据点 + P50/P95）→ LLM 批量设定检测规则（upGate/downGate/thresholdPercent）。
   * 仅配置期调用，返回候选清单待人工确认后经 saveMetrics 落库；规则生成失败时降级为全局默认阈值，不阻塞展开。
   */
  private List<Map<String, Object>> probeWhitelist(String app, List<Map<String, Object>> entries, Map<String, Object> queryTool, Instant now) {
    var items = new ArrayList<Map<String, Object>>();
    for (var entry : entries) {
      String promql = s(entry, "promql").replace("{app}", app);
      var item =
          obj(
              "category",
              s(entry, "category"),
              "name",
              s(entry, "name"),
              "promql",
              promql,
              "unit",
              s(entry, "unit"),
              "upGate",
              null,
              "downGate",
              null,
              "thresholdPercent",
              null,
              "valid",
              false,
              "p50",
              null,
              "p95",
              null,
              "samples",
              0);
      try {
        var points =
            series(
                mcp.call(
                    queryTool,
                    obj(
                        "query",
                        promql,
                        "start",
                        BEIJING.format(now.minus(Duration.ofHours(1))),
                        "end",
                        BEIJING.format(now),
                        "step",
                        300)));
        if (points.size() >= 2) {
          double[] values = points.stream().mapToDouble(Point::value).sorted().toArray();
          item.put("valid", true);
          item.put("p50", round4(percentile(values, 0.5)));
          item.put("p95", round4(percentile(values, 0.95)));
          item.put("samples", points.size());
        }
      } catch (Exception e) {
        /* 探针失败保留 valid=false，下一扫描轮重试 */
      }
      items.add(item);
    }
    return items;
  }

  /** LLM 批量设定检测规则；仅对探针有效的候选，失败时保持 null（回落全局阈值）并注明原因。 */
  private void applyRules(String service, List<Map<String, Object>> items) {
    var candidates = items.stream().filter(i -> Boolean.TRUE.equals(i.get("valid"))).toList();
    if (candidates.isEmpty()) return;
    String system =
        "你是 SRE 智能巡检的检测规则设定者。依据每个指标的探针统计与业务含义，为每个指标设定检测规则：\n"
            + "- upGate：指标上升异常时当前值须达到的绝对水位；downGate：指标下降异常时当前值须低于的绝对水位；不需要的方向给 null\n"
            + "- thresholdPercent：相对突变敏感度（%），基线突变超过该百分比才进入异常判定；噪声大给高值，平稳给低值\n"
            + "- upGate/downGate 必须与指标序列同一数值空间：对照 p50/p95 的实际量级设定，不要按 unit 字面猜测量级\n"
            + "- 事件类指标（OOM、异常连接、异常请求等，正常为 0 或低位）：upGate 给最小显著值（如 1）\n"
            + "- 成功率/命中率类（正常接近 1）：downGate 给显著下限\n"
            + "- 拿不准就给 null（null 回落全局 thresholdPercent，或该方向不过滤）\n"
            + "只输出 JSON 对象，格式 {\"rules\":[{\"name\",\"upGate\",\"downGate\",\"thresholdPercent\",\"reason\"}]}，"
            + "name 必须来自输入清单，reason 一句话说明设定依据。";
    try {
      var parsed =
          extractJson(
              model.answer(
                  system,
                  "应用：" + service + "\n指标探针统计：\n" + encode(obj("metrics", candidates))));
      if (parsed.get("rules") instanceof List<?> list) {
        var names = candidates.stream().map(c -> s(c, "name")).collect(Collectors.toSet());
        for (var e : list) {
          if (!(e instanceof Map<?, ?> raw)) continue;
          var rule = map(raw);
          String name = s(rule, "name");
          if (!names.contains(name)) continue; // 杜撰名剔除
          var item = candidates.stream().filter(c -> s(c, "name").equals(name)).findFirst().orElseThrow();
          item.put("upGate", positiveOrNull(rule, "upGate"));
          item.put("downGate", positiveOrNull(rule, "downGate"));
          item.put("thresholdPercent", positiveOrNull(rule, "thresholdPercent"));
          item.put("reason", s(rule, "reason"));
        }
      }
    } catch (Exception e) {
      for (var c : candidates) c.put("reason", "规则生成失败，使用全局默认阈值");
    }
  }

  private static Double positiveOrNull(Map<String, Object> m, String key) {
    Double v = nullableDouble(m, key);
    return v != null && v > 0 ? v : null;
  }

  public List<Map<String, Object>> findings(String owner) {
    return store.list("inspection_finding", owner, null).stream()
        .sorted(Comparator.comparing((Map<String, Object> f) -> s(f, "detectedAt")).reversed())
        .limit(200)
        .toList();
  }

  public void tick() {
    try {
      if (!enabled()) return;
      var section = map(config.section("inspection"));
      var ownerId = str(section.get("ownerId")).trim();
      var apps = apps(section);
      if (ownerId.isBlank() || apps.isEmpty()) return;
      if (!due(section, ownerId)) return;
      runRound();
      db.update("UPDATE inspection_settings SET last_scan_at=? WHERE id=1", now());
    } catch (Exception ignored) {
      /* 下一轮 tick 重试 */
    }
  }

  public void runRound() {
    var section = map(config.section("inspection"));
    var ownerId = str(section.get("ownerId")).trim();
    var apps = apps(section);
    if (ownerId.isBlank() || apps.isEmpty()) return;
    Map<String, Map<String, Object>> tools;
    try {
      tools = mcp.tools(ownerId);
    } catch (Exception e) {
      return;
    }
    var tool = tools.get(QUERY_TOOL);
    if (tool == null) return;
    Instant now = Instant.now();
    Set<String> selected = selectedMetrics();
    int confirmed = 0;
    int scanned = 0;
    for (String app : apps) {
      List<Metric> metrics = effectiveMetrics(ownerId, app, selected, tool, now);
      scanned += metrics.size();
      for (Metric metric : metrics) {
        try {
          if (evaluate(ownerId, app, metric, tool, section, now)) confirmed++;
        } catch (Exception e) {
          /* 单指标失败不影响其他扫描 */
        }
      }
    }
    log.info("inspection: apps={} scanned={} confirmed={}", apps.size(), scanned, confirmed);
  }

  /**
   * 单服务生效指标：读注册清单，按勾选指标名过滤；
   * 勾选范围内尚未注册的白名单条目先自动补注册——逐条探针（近 1 小时）+ 一次 LLM 批量定规则，幂等。
   */
  private List<Metric> effectiveMetrics(
      String ownerId, String app, Set<String> selected, Map<String, Object> tool, Instant now) {
    List<Metric> registered;
    try {
      var reg = map(store.get("inspection_metric", ownerId, app));
      registered = parseRegistered(maps(reg.get("metrics")));
    } catch (Exception e) {
      registered = List.of();
    }
    var wanted =
        whitelist.stream()
            .filter(e -> selected == null || selected.contains(s(e, "name")))
            .toList();
    var known = registered.stream().map(Metric::key).collect(Collectors.toSet());
    var missing = wanted.stream().filter(e -> !known.contains(s(e, "name"))).toList();
    if (!missing.isEmpty()) {
      try {
        var items = probeWhitelist(app, missing, tool, now);
        applyRules(app, items);
        var merged = new ArrayList<>(registered.stream().map(this::metricMap).toList());
        for (var item : items) {
          if (!Boolean.TRUE.equals(item.get("valid"))) continue;
          merged.add(
              obj(
                  "name",
                  s(item, "name"),
                  "promql",
                  s(item, "promql"),
                  "unit",
                  s(item, "unit"),
                  "upGate",
                  item.get("upGate"),
                  "downGate",
                  item.get("downGate"),
                  "thresholdPercent",
                  item.get("thresholdPercent"),
                  "category",
                  s(item, "category")));
        }
        if (merged.size() > registered.size()) {
          store.put(
              "inspection_metric",
              ownerId,
              "",
              obj("id", app, "service", app, "metrics", merged, "updatedAt", now()));
          registered = parseRegistered(merged);
          log.info("inspection: app={} registered={} metrics", app, merged.size());
        }
      } catch (Exception e) {
        log.warn("inspection: app={} 自动补注册失败，下一扫描轮重试", app, e);
      }
    }
    return registered.stream()
        .filter(m -> selected == null || selected.contains(m.key()))
        .toList();
  }

  private boolean evaluate(
      String ownerId, String app, Metric metric, Map<String, Object> tool,
      Map<String, Object> section, Instant now)
      throws Exception {
    int window = numberOr(section, "windowMinutes", 5, 1);
    double threshold =
        metric.thresholdPercent() != null
            ? metric.thresholdPercent() / 100.0
            : numberOr(section, "thresholdPercent", 30, 1) / 100.0;
    String promql = metric.promql().replace("{app}", app);
    var output =
        mcp.call(
            tool,
            obj(
                "query",
                promql,
                "start",
                BEIJING.format(now.minus(Duration.ofMinutes(2L * window))),
                "end",
                BEIJING.format(now),
                "step",
                60));
    var points = series(output);
    var baseline = points.stream().filter(p -> p.at().isBefore(now.minus(Duration.ofMinutes(window)))).toList();
    var current = points.stream().filter(p -> !p.at().isBefore(now.minus(Duration.ofMinutes(window)))).toList();
    if (baseline.size() < 2 || current.size() < 2) return false;
    double base = baseline.stream().mapToDouble(Point::value).average().orElse(Double.NaN);
    double cur = current.stream().mapToDouble(Point::value).average().orElse(Double.NaN);
    if (Double.isNaN(base) || Double.isNaN(cur)) return false;
    double ratio = (cur - base) / Math.max(Math.abs(base), 1e-9);
    String key = stateId(app + "|" + metric.key());
    if (Math.abs(ratio) < threshold) {
      recover(ownerId, app, metric.key());
      try {
        store.delete("inspection_scan_state", ownerId, key);
      } catch (Exception e) {
        /* 状态不存在则无需清理 */
      }
      return false;
    }
    String dir = ratio > 0 ? "up" : "down";
    if (!significant(dir, cur, metric)) {
      // 偏移比过线但绝对值未达门槛：统计显著≠业务显著，视同无异常走恢复
      recover(ownerId, app, metric.key());
      try {
        store.delete("inspection_scan_state", ownerId, key);
      } catch (Exception e) {
        /* 状态不存在则无需清理 */
      }
      return false;
    }
    Map<String, Object> state;
    try {
      state = store.get("inspection_scan_state", ownerId, key);
    } catch (Exception e) {
      state = Map.of();
    }
    boolean confirm =
        s(state, "lastDirection").equals(dir)
            && !s(state, "updatedAt").isBlank()
            && Instant.parse(s(state, "updatedAt"))
                .isAfter(now.minus(Duration.ofMinutes(4L * window)));
    store.put(
        "inspection_scan_state", ownerId, "",
        obj(
            "id",
            key,
            "service",
            app,
            "metricKey",
            metric.key(),
            "lastDirection",
            dir,
            "lastRatio",
            ratio,
            "updatedAt",
            now()));
    if (!confirm) return false;
    return record(ownerId, app, metric, dir, ratio, base, cur, window, now);
  }

  static boolean significant(String dir, double cur, Metric metric) {
    if (dir.equals("up")) return metric.upGate() == null || cur >= metric.upGate();
    return metric.downGate() == null || cur <= metric.downGate();
  }

  private boolean record(
      String ownerId, String app, Metric metric, String dir, double ratio, double base,
      double cur, int window, Instant now) {
    var existing =
        store.list("inspection_finding", ownerId, null).stream()
            .filter(f -> s(f, "service").equals(app) && s(f, "metricKey").equals(metric.key()))
            .filter(f -> s(f, "status").equals("open"))
            .findFirst();
    double pct = Math.round(ratio * 10000) / 100.0;
    if (existing.isPresent()) {
      var f = existing.get();
      f.put("direction", dir);
      f.put("deltaPercent", pct);
      f.put("currentValue", round4(cur));
      f.put("lastSeenAt", now());
      store.put("inspection_finding", ownerId, "", f);
      return false;
    }
    String diagnosticId = null;
    var sec = map(config.section("inspection"));
    if (!Boolean.FALSE.equals(sec.get("autoDiagnose"))) {
      String query =
          "智能巡检发现异常：服务 "
              + app
              + " 的 "
              + metric.name()
              + (dir.equals("up") ? " 突然上升 " : " 突然下降 ")
              + pct
              + "%（"
              + round4(base)
              + " → "
              + round4(cur)
              + " "
              + metric.unit()
              + "），请排查根因。";
      try {
        var task = diagnosis.create(ownerId, obj("query", query, "caseEnabled", false));
        diagnosticId = s(task, "id");
      } catch (Exception e) {
        /* 诊断创建失败不阻塞发现记录 */
      }
    }
    String alertName = "";
    try {
      alertName =
          alerts.active().stream()
              .filter(a -> s(a, "service").equals(app))
              .findFirst()
              .map(a -> s(a, "alertName"))
              .orElse("");
    } catch (Exception e) {
      /* 告警旁证尽力而为 */
    }
    store.put(
        "inspection_finding",
        ownerId,
        "",
        obj(
            "id",
            id("finding"),
            "service",
            app,
            "metricKey",
            metric.key(),
            "metricName",
            metric.name(),
            "unit",
            metric.unit(),
            "promql",
            metric.promql().replace("{app}", app),
            "direction",
            dir,
            "deltaPercent",
            pct,
            "baselineValue",
            round4(base),
            "currentValue",
            round4(cur),
            "windowMinutes",
            window,
            "status",
            "open",
            "detectedAt",
            now(),
            "lastSeenAt",
            now(),
            "recoveredAt",
            null,
            "diagnosticId",
            diagnosticId,
            "alertName",
            alertName));
    return true;
  }

  private void recover(String ownerId, String app, String metricKey) {
    store.list("inspection_finding", ownerId, null).stream()
        .filter(f -> s(f, "service").equals(app) && s(f, "metricKey").equals(metricKey))
        .filter(f -> s(f, "status").equals("open"))
        .findFirst()
        .ifPresent(
            f -> {
              f.put("status", "recovered");
              f.put("recoveredAt", now());
              store.put("inspection_finding", ownerId, "", f);
            });
  }

  record Point(Instant at, double value) {}

  private List<Point> series(String output) throws Exception {
    var out = new ArrayList<Point>();
    for (var c : maps(decode(output).get("content"))) {
      String text = str(c.get("text"));
      if (text.isBlank()) continue;
      var root = mapper.readValue(text, Map.class);
      for (var s0 : maps(root.get("data")))
        for (var entry : (List<?>) map(s0).getOrDefault("values", List.of())) {
          var pair = (List<?>) entry;
          out.add(new Point(Instant.from(BEIJING.parse(str(pair.get(0)))), Double.parseDouble(str(pair.get(1)))));
        }
      break;
    }
    out.sort(Comparator.comparing(Point::at));
    return out;
  }

  private boolean enabled() {
    var rows = db.queryForList("SELECT enabled FROM inspection_settings WHERE id=1");
    return !rows.isEmpty() && ((Number) rows.get(0).get("enabled")).intValue() == 1;
  }

  private boolean due(Map<String, Object> section, String ownerId) {
    int interval = numberOr(section, "intervalMinutes", 5, 1);
    var rows = db.queryForList("SELECT last_scan_at FROM inspection_settings WHERE id=1");
    if (rows.isEmpty()) return false;
    String last = str(rows.get(0).get("last_scan_at"));
    if (last.isBlank()) return true;
    return Instant.parse(last).plus(Duration.ofMinutes(interval)).isBefore(Instant.now());
  }

  private static String ownerId(Map<String, Object> section) {
    return str(section.get("ownerId")).trim();
  }

  private List<Metric> parseRegistered(List<Map<String, Object>> list) {
    if (list.size() > 20) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var out = new ArrayList<Metric>();
    for (var m : list) {
      String name = s(m, "name").trim();
      String promql = s(m, "promql").trim();
      if (name.isBlank() || promql.isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
      out.add(
          new Metric(
              name,
              name,
              promql,
              s(m, "unit"),
              nullableDouble(m, "upGate"),
              nullableDouble(m, "downGate"),
              nullableDouble(m, "thresholdPercent"),
              s(m, "category")));
    }
    return out;
  }

  private Map<String, Object> metricMap(Metric m) {
    return obj(
        "name",
        m.key(),
        "promql",
        m.promql(),
        "unit",
        m.unit(),
        "upGate",
        m.upGate(),
        "downGate",
        m.downGate(),
        "thresholdPercent",
        m.thresholdPercent(),
        "category",
        m.category());
  }

  private List<Map<String, Object>> loadWhitelist() {
    try (var in = InspectionService.class.getResourceAsStream("/inspection/whitelist.json")) {
      var root = mapper.readValue(in, Map.class);
      var list = (List<?>) root.get("metrics");
      if (list == null || list.isEmpty()) throw new IllegalStateException("巡检白名单为空");
      return list.stream().map(e -> map(e)).toList();
    } catch (Exception e) {
      throw new IllegalStateException("巡检白名单加载失败", e);
    }
  }

  private Map<String, Object> extractJson(String text) {
    if (text == null || text.isBlank()) throw new ApiException("SYSTEM_UNAVAILABLE", "LLM 返回为空");
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) throw new ApiException("SYSTEM_UNAVAILABLE", "LLM 返回缺少 JSON");
    try {
      return mapper.readValue(text.substring(start, end + 1), Map.class);
    } catch (Exception e) {
      throw new ApiException("SYSTEM_UNAVAILABLE", "LLM 返回 JSON 无法解析");
    }
  }

  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return Double.NaN;
    int idx = (int) Math.ceil(p * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
  }

  private List<String> apps(Map<String, Object> section) {
    Object v = section.get("apps");
    if (!(v instanceof List<?> list)) return List.of();
    return list.stream()
        .map(e -> e instanceof Map<?, ?> m ? str(m.get("name")).trim() : str(e).trim())
        .filter(e -> !e.isEmpty())
        .toList();
  }

  static String stateId(String key) {
    return "scanstate-" + Integer.toHexString(key.hashCode());
  }

  private static double round4(double v) {
    return Math.round(v * 10000) / 10000.0;
  }

  private static int numberOr(Map<String, Object> m, String key, int fallback, int min) {
    Object v = m.get(key);
    if (v instanceof Number num && num.intValue() >= min) return num.intValue();
    return fallback;
  }

  private static Double nullableDouble(Map<String, Object> m, String key) {
    return m.get(key) instanceof Number num ? num.doubleValue() : null;
  }
}
