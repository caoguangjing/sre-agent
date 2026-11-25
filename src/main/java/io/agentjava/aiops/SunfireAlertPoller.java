package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import io.agentjava.config.ProjectConfig;
import io.agentjava.mcp.McpService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 拉取模式接入 Sunfire 告警：定时通过 MCP queryAlarmList 查询配置应用的报警列表，
 * 写入 SunfireAlertStore（与回调通道共用存储/去重/TTL）。线上→日常入站不通，无法使用回调推送。
 */
@Component
public class SunfireAlertPoller {
  private static final Logger log = LoggerFactory.getLogger(SunfireAlertPoller.class);
  static final DateTimeFormatter BEIJING =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
  static final String QUERY_TOOL = "queryAlarmList";

  private final ProjectConfig config;
  private final McpService mcp;
  private final SunfireAlertStore store;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "sunfire-alert-poll");
            t.setDaemon(true);
            return t;
          });

  public SunfireAlertPoller(ProjectConfig config, McpService mcp, SunfireAlertStore store) {
    this.config = config;
    this.mcp = mcp;
    this.store = store;
  }

  @PostConstruct
  void start() {
    var poll = settings();
    if (!enabled(poll)) return;
    int interval = numberOr(poll, "intervalSeconds", 60, 10);
    log.info("sunfirePoll: 启用，apps={} 间隔={}s", apps(poll), interval);
    scheduler.scheduleWithFixedDelay(this::pollRound, 20, interval, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }

  public void pollRound() {
    var poll = settings();
    if (!enabled(poll)) return;
    String owner = s(poll, "ownerId");
    var list = apps(poll);
    if (owner.isBlank()) return;
    Map<String, Map<String, Object>> tools;
    try {
      tools = mcp.tools(owner);
    } catch (Exception e) {
      log.warn("sunfirePoll: MCP 连接查询失败 {}", config.safe(e));
      return;
    }
    var tool = tools.get(QUERY_TOOL);
    if (tool == null) {
      log.warn("sunfirePoll: 启用的 MCP 连接未提供 {} 工具", QUERY_TOOL);
      return;
    }
    int lookback = numberOr(poll, "lookbackMinutes", 10, 1);
    Instant end = Instant.now();
    Instant start = end.minus(lookback, ChronoUnit.MINUTES);
    int ingested = 0, suppressed = 0;
    for (String app : list) {
      try {
        String output =
            mcp.call(
                tool,
                obj(
                    "request",
                    obj("appName", app, "start", BEIJING.format(start), "end", BEIJING.format(end)),
                    "page",
                    1,
                    "limit",
                    50));
        for (var record : alarms(output)) {
          if (s(record, "errorMsg").startsWith("[报警抑制]") || s(record, "alarmId").isBlank()) {
            suppressed++;
            continue;
          }
          var payload = new LinkedHashMap<>(record);
          payload.put("latestAlarmId", s(record, "alarmId"));
          // 报警规则每分钟合并触发会产生新 alarmId，按 alarmRuleId 归并成一条持续告警，最新触发覆盖旧态
          payload.put("alarmId", firstNonBlank(record, "alarmRuleId", "alarmId"));
          payload.put("appName", app);
          store.save(payload);
          ingested++;
        }
      } catch (Exception e) {
        log.warn("sunfirePoll: {} 拉取失败 {}", app, config.safe(e));
      }
    }
    if (ingested + suppressed > 0)
      log.info("sunfirePoll: apps={} ingested={} suppressed={}", list, ingested, suppressed);
  }

  private Map<String, Object> settings() {
    return map(config.section("prometheusAlerts").get("sunfirePoll"));
  }

  private static boolean enabled(Map<String, Object> poll) {
    return "true".equalsIgnoreCase(s(poll, "enabled")) && !apps(poll).isEmpty();
  }

  private static List<String> apps(Map<String, Object> poll) {
    if (!(poll.get("apps") instanceof List<?> list)) return List.of();
    return list.stream().map(a -> s(obj("v", a), "v")).filter(a -> !a.isBlank()).toList();
  }

  private static int numberOr(Map<String, Object> poll, String key, int fallback, int min) {
    return poll.get(key) instanceof Number num && num.intValue() >= min
        ? num.intValue()
        : fallback;
  }

  private static String firstNonBlank(Map<String, Object> record, String... keys) {
    for (String key : keys) if (!s(record, key).isBlank()) return s(record, key);
    return "";
  }

  List<Map<String, Object>> alarms(String output) {
    try {
      for (var block : maps(decode(output).get("content"))) {
        String text = s(block, "text");
        if (text.isBlank()) continue;
        Object parsed = MAPPER.readValue(text, Object.class);
        if (parsed instanceof List<?> list) return castMaps(list);
        if (parsed instanceof Map<?, ?> wrapper)
          for (String key : List.of("data", "list", "records", "result"))
            if (wrapper.get(key) instanceof List<?> inner) return castMaps(inner);
        return List.of();
      }
    } catch (Exception e) {
      log.warn("sunfirePoll: 结果解析失败 {}", config.safe(e));
    }
    return List.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castMaps(List<?> list) {
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item)
        .toList();
  }
}
