package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import io.agentjava.config.ProjectConfig;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** SLS scope and time bounds come from the request/configuration, not model arithmetic. */
final class SlsQueryContext {
  static final String SQL_RULES =
      "SLS查询语句由检索语句和可选分析SQL组成，格式为\"检索语句 | 分析SQL\"，只检索日志时省略分析SQL。"
          + "SLS的__time__是Unix秒BIGINT，不是毫秒，不能按毫秒换算。"
          + "按分钟统计优先使用date_trunc('minute', __time__)并GROUP BY该别名，"
          + "例如：* | SELECT date_trunc('minute', __time__) AS minute, count(*) AS cnt GROUP BY minute ORDER BY minute。"
          + "格式化时可使用date_format(from_unixtime(__time__), '%H:%i')。"
          + "日志JSON中的timestamp等业务字段可能是字符串，不能假定其类型。"
          + "查询失败时依据真实错误修正SQL，不得将失败的查询描述为成功证据。";
  private static final Pattern PLACEHOLDER =
      Pattern.compile("(?i)(待替换|待填|placeholder|<[A-Za-z_][A-Za-z0-9_ -]*>|\\$\\{|\\{\\{)");
  private static final Set<String> SCOPE_TOOLS =
      Set.of("sls_list_logstores", "sls_query_logs", "sls_get_log_histogram", "sls_get_context_logs");
  private static final Set<String> TIME_TOOLS = Set.of("sls_query_logs", "sls_get_log_histogram");
  private final Map<String, Object> scope;

  SlsQueryContext(ProjectConfig config, Map<String, Object> task) {
    var alert = map(map(task.get("inputPayload")).get("alert"));
    var annotations = map(alert.get("annotations"));
    String query = s(task, "query");
    String project = named(query, "Project|slsProject", "[a-zA-Z0-9_-]+");
    String logstore = named(query, "Logstore|slsLogstore", "[a-zA-Z0-9_-]+");
    if (project.isBlank()) project = s(annotations, "slsProject");
    if (logstore.isBlank()) logstore = s(annotations, "slsLogstore");
    if (project.isBlank()) project = config.text("slsLogUpload", "project");
    if (logstore.isBlank()) logstore = config.text("slsLogUpload", "logstore");
    long to = Instant.parse(s(task, "createdAt")).getEpochSecond();
    long from = to - Duration.ofMinutes(15).toSeconds();
    var recent = Pattern.compile("(?:最近|近|过去)\\s*(\\d+)\\s*(分钟|小时|天)").matcher(query);
    if (recent.find()) {
      long amount = Long.parseLong(recent.group(1));
      long unit = switch (recent.group(2)) {
        case "小时" -> 3_600L;
        case "天" -> 86_400L;
        default -> 60L;
      };
      from = to - Math.multiplyExact(amount, unit);
    } else if (!s(alert, "startsAt").isBlank()) {
      try {
        from = Math.min(to - 60, Instant.parse(s(alert, "startsAt")).getEpochSecond() - 300);
      } catch (DateTimeException ignored) {
      }
    }
    String explicitFrom = named(query, "From", "\\d{10}(?!\\d)"), explicitTo = named(query, "To", "\\d{10}(?!\\d)");
    if (!explicitFrom.isBlank() && !explicitTo.isBlank()) {
      from = Long.parseLong(explicitFrom);
      to = Long.parseLong(explicitTo);
    } else {
      var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})?").matcher(query);
      var times = new ArrayList<Long>();
      while (dates.find()) times.add(timestamp(dates.group()));
      if (times.size() >= 2) {
        from = times.get(0);
        to = times.get(1);
      }
    }
    if (from < 0 || from >= to) throw new IllegalArgumentException("SLS查询时间范围无效：开始时间须早于结束时间");
    scope = obj("project", project, "logstore", logstore, "from", from, "to", to);
  }

  Map<String, Object> scope() {
    return scope;
  }

  Map<String, Object> arguments(
      String tool, Map<String, Object> planned, Map<String, Object> schema) {
    var args = new LinkedHashMap<>(planned);
    if (SCOPE_TOOLS.contains(tool)) {
      var properties = map(schema.get("properties"));
      for (String key : List.of("project", "logstore"))
        if (!s(scope, key).isBlank() && (properties.containsKey(key) || args.containsKey(key)))
          args.put(key, scope.get(key));
      if (TIME_TOOLS.contains(tool)) {
        for (String key : List.of("from", "to"))
          if (properties.containsKey(key) || args.containsKey(key))
            args.put(key, tool.equals("sls_query_logs") ? String.valueOf(scope.get(key)) : scope.get(key));
        if (args.containsKey("from") || args.containsKey("to")) {
          Long from = seconds(args.get("from")), to = seconds(args.get("to"));
          if (from == null || to == null || from >= to)
            throw new IllegalArgumentException(tool + "时间范围无效");
        }
      }
    }
    if (containsPlaceholder(args))
      throw new IllegalArgumentException("工具参数仍含未解析的占位值，未发送查询：" + tool);
    for (Object key : (List<?>) schema.getOrDefault("required", List.of()))
      if (!args.containsKey(str(key)) || args.get(str(key)) == null
          || args.get(str(key)) instanceof String value && value.isBlank())
        throw new IllegalArgumentException("工具缺少必填参数：" + tool + "." + key);
    return args;
  }

  private static Long seconds(Object value) {
    try {
      if (value instanceof Number n) return n.longValue();
      String t = str(value);
      return t.matches("\\d+") ? Long.parseLong(t) : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean containsPlaceholder(Object value) {
    if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(SlsQueryContext::containsPlaceholder);
    if (value instanceof List<?> list) return list.stream().anyMatch(SlsQueryContext::containsPlaceholder);
    return value instanceof String text && PLACEHOLDER.matcher(text).find();
  }

  private static String named(String query, String names, String value) {
    var match = Pattern.compile("(?i)(?:" + names + ")\\s*[=:：]\\s*[\"']?(" + value + ")").matcher(query);
    return match.find() ? match.group(1) : "";
  }

  private static long timestamp(String text) {
    text = text.replace(' ', 'T');
    try {
      return OffsetDateTime.parse(text).toInstant().getEpochSecond();
    } catch (DateTimeException e) {
      return LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
    }
  }
}
