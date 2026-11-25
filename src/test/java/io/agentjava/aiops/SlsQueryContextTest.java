package io.agentjava.aiops;

import static io.agentjava.store.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentjava.config.ProjectConfig;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SlsQueryContextTest {
  @TempDir Path root;
  ProjectConfig config;
  Map<String, Object> schema =
      obj("properties", obj("project", obj(), "logstore", obj(), "query", obj(), "from", obj(), "to", obj()),
          "required", List.of("project", "logstore", "from", "to"));

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("config"));
    Files.writeString(root.resolve("config/project.json"), encode(obj("slsLogUpload", obj("project", "configured-project", "logstore", "configured-logstore"))));
    Files.writeString(root.resolve("config/user.project.json"), "{}");
    config = new ProjectConfig(root);
  }

  @Test
  void alertScopeAndServerTimeReplaceGuessedValues() {
    var task = obj("query", "检查网关告警", "createdAt", "2026-10-04T14:14:32Z", "inputPayload",
        obj("alert", obj("startsAt", "2026-10-04T14:09:26Z", "annotations",
            obj("slsProject", "alert-project", "slsLogstore", "alert-logstore"))));
    var args = new SlsQueryContext(config, task).arguments("sls_query_logs",
        obj("project", "guessed", "logstore", "待替换为Logstore", "query", "* | SELECT count(*)", "from", "now-1h", "to", "now"), schema);
    assertEquals("alert-project", args.get("project"));
    assertEquals("alert-logstore", args.get("logstore"));
    assertEquals(String.valueOf(Instant.parse("2026-10-04T14:04:26Z").getEpochSecond()), args.get("from"));
    assertEquals(String.valueOf(Instant.parse("2026-10-04T14:14:32Z").getEpochSecond()), args.get("to"));
    assertEquals("* | SELECT count(*)", args.get("query"));
  }

  @Test
  void explicitHistoricalBoundsAndScopeOverrideDefaults() {
    var task = obj("query", "Project=demo-project Logstore=user-logstore 查询2026-10-04T19:00:00+08:00到2026-10-04T20:00:00+08:00",
        "createdAt", "2026-10-04T14:14:32Z");
    var args = new SlsQueryContext(config, task).arguments("sls_query_logs", obj("query", "*"), schema);
    assertEquals("demo-project", args.get("project"));
    assertEquals("user-logstore", args.get("logstore"));
    assertEquals(String.valueOf(Instant.parse("2026-10-04T11:00:00Z").getEpochSecond()), args.get("from"));
    assertEquals(String.valueOf(Instant.parse("2026-10-04T12:00:00Z").getEpochSecond()), args.get("to"));
  }

  @Test
  void numericUnixSecondsRemainLongAndRecentWindowIsBounded() {
    var task = obj("query", "From=1791111600 To=1791115200", "createdAt", "2026-10-04T14:14:32Z");
    var scope = new SlsQueryContext(config, task).scope();
    assertEquals(1791111600L, scope.get("from"));
    assertEquals(1791115200L, scope.get("to"));
    task.put("query", "最近2小时日志");
    scope = new SlsQueryContext(config, task).scope();
    assertEquals(7_200L, (Long) scope.get("to") - (Long) scope.get("from"));
  }

  @Test
  void histogramReceivesNumericUnixSeconds() {
    var context = new SlsQueryContext(config, obj("query", "最近30分钟日志", "createdAt", "2026-10-04T14:14:32Z"));
    var args = context.arguments("sls_get_log_histogram", obj("from", "now", "to", "now"), schema);
    assertEquals(1_800L, (Long) args.get("to") - (Long) args.get("from"));
  }

  @Test
  void unresolvedQueryAndMissingRequiredArgumentsFailBeforeCall() {
    var context = new SlsQueryContext(config, obj("query", "查日志", "createdAt", "2026-10-04T14:14:32Z"));
    assertThrows(IllegalArgumentException.class,
        () -> context.arguments("sls_query_logs", obj("query", "placeholder-query"), schema));
    assertThrows(IllegalArgumentException.class,
        () -> context.arguments("sls_list_logstores", obj(), obj("properties", obj(), "required", List.of("project"))));
  }
}
