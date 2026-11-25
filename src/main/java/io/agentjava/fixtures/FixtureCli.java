package io.agentjava.fixtures;

import static io.agentjava.store.Json.*;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.LogContent;
import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.request.PutLogsRequest;
import io.agentjava.config.ProjectConfig;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 显式开发夹具入口；普通应用启动不会执行。 */
public final class FixtureCli {
  public static List<Map<String, Object>> incidents() {
    try {
      return maps(
          MAPPER.readValue(
              FixtureCli.class.getResourceAsStream("/fixtures/incidents.json"), List.class));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static List<Map<String, Object>> logs(List<Map<String, Object>> incidents, int count) {
    if (count < 1 || count > 100) throw new IllegalArgumentException("日志数须为1~100");
    List<Map<String, Object>> rows = new ArrayList<>();
    long time = System.currentTimeMillis() / 1000;
    for (int i = 0; i < count; i++) {
      var incident = incidents.get(i % incidents.size());
      var fields = new LinkedHashMap<>(incident);
      fields.remove("investigation_steps");
      fields.remove("recovery_steps");
      fields.putAll(
          obj(
              "level",
              "ERROR",
              "message",
              incident.get("symptom") + "; " + incident.get("exception"),
              "timestamp",
              Instant.ofEpochSecond(time).toString(),
              "fixture",
              "agent-java-ecommerce",
              "host",
              incident.get("service") + "-01"));
      rows.add(fields);
    }
    return rows;
  }

  public static String sop(Map<String, Object> i) {
    return "# "
        + s(i, "service")
        + " 运维SOP\n\nincident_id: "
        + s(i, "incident_id")
        + "\ntrace_id: "
        + s(i, "trace_id")
        + "\nsop_id: "
        + s(i, "sop_id")
        + "\n\n## 症状\n"
        + s(i, "symptom")
        + "\n\n## 根因候选（需真实日志确认）\n"
        + s(i, "root_cause")
        + "\n\n## 排查步骤\n"
        + encode(i.get("investigation_steps"))
        + "\n\n## 恢复建议\n"
        + encode(i.get("recovery_steps"))
        + "\n\n## 验证\n"
        + s(i, "verification");
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1)
      throw new IllegalArgumentException("fixtures generate|upload|alerts|seed [count]");
    var config = new ProjectConfig();
    var incidents = incidents();
    String action = args[0];
    int count = args.length > 1 ? Integer.parseInt(args[1]) : 30;
    switch (action) {
      case "generate" -> {
        Path folder = config.root.resolve("var/fixtures");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("logs.json"), encode(logs(incidents, count)));
        for (var i : incidents) Files.writeString(folder.resolve(s(i, "sop_id") + ".md"), sop(i));
        System.out.println("生成10套SOP及" + count + "条显式夹具日志");
      }
      case "upload" -> {
        var client =
            new Client(
                URI.create(config.text("slsLogUpload", "endpoint")).getHost(),
                config.text("slsLogUpload", "accessKeyId"),
                config.text("slsLogUpload", "accessKeySecret"));
        List<LogItem> items = new ArrayList<>();
        for (var row : logs(incidents, count)) {
          LogItem item = new LogItem((int) (System.currentTimeMillis() / 1000));
          row.forEach((k, v) -> item.PushBack(new LogContent(k, str(v))));
          items.add(item);
        }
        client.PutLogs(new PutLogsRequest(
            config.text("slsLogUpload", "project"),
            config.text("slsLogUpload", "logstore"),
            "",
            "",
            items));
        System.out.println("已上传" + count + "条合成开发日志到真实SLS");
      }
      case "alerts" -> {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (var i : incidents)
          alerts.add(
              obj(
                  "labels",
                  obj(
                      "alertname",
                      i.get("alert_name"),
                      "service",
                      i.get("service"),
                      "severity",
                      i.get("severity"),
                      "incident_id",
                      i.get("incident_id"),
                      "trace_id",
                      i.get("trace_id"),
                      "sop_id",
                      i.get("sop_id"),
                      "fixture",
                      "agent-java-ecommerce"),
                  "annotations",
                  obj(
                      "summary",
                      i.get("symptom"),
                      "description",
                      i.get("metric_name") + "=" + i.get("metric_value"),
                      "slsProject",
                      config.text("slsLogUpload", "project"),
                      "slsLogstore",
                      config.text("slsLogUpload", "logstore")),
                  "startsAt",
                  Instant.now().minusSeconds(300).toString(),
                  "endsAt",
                  Instant.now().plusSeconds(3600).toString()));
        var r =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:9093/api/v2/alerts"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(encode(alerts)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new IllegalStateException("告警发布失败");
        System.out.println("已发布10个真实Alertmanager开发告警");
      }
      case "seed" -> {
        seed(config, incidents);
      }
      default -> throw new IllegalArgumentException("未知fixtures操作");
    }
  }

  private static void seed(ProjectConfig config, List<Map<String, Object>> incidents)
      throws Exception {
    String base = config.text("frontend", "apiBaseUrl");
    var login =
        api(
            base,
            "/auth/login",
            null,
            obj(
                "email",
                config.text("aiopsDemo", "email"),
                "password",
                config.text("aiopsDemo", "password")));
    if (!Boolean.TRUE.equals(login.get("ok")))
      login =
          api(
              base,
              "/auth/register",
              null,
              obj(
                  "email",
                  config.text("aiopsDemo", "email"),
                  "displayName",
                  config.text("aiopsDemo", "displayName"),
                  "password",
                  config.text("aiopsDemo", "password")));
    if (!Boolean.TRUE.equals(login.get("ok"))) throw new IllegalStateException("演示账号认证失败");
    String token = s(map(login.get("data")), "accessToken"),
        uid = s(map(map(login.get("data")).get("user")), "id");
    for (var i : incidents) {
      String boundary = "JavaFixture" + UUID.randomUUID();
      String content =
          "--"
              + boundary
              + "\r\n"
              + "Content-Disposition: form-data; name=\"chunking\"\r\n\r\n"
              + "{\"strategy\":\"markdown-heading\"}\r\n"
              + "--"
              + boundary
              + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
              + s(i, "sop_id")
              + ".md\"\r\nContent-Type: text/markdown\r\n\r\n"
              + sop(i)
              + "\r\n--"
              + boundary
              + "--\r\n";
      var r =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(base + "/knowledge-bases/kb_" + uid + "/documents"))
                      .header("Authorization", "Bearer " + token)
                      .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                      .POST(HttpRequest.BodyPublishers.ofString(content))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() == 409) continue;
      var result = decode(r.body());
      if (!Boolean.TRUE.equals(result.get("ok"))) throw new IllegalStateException("SOP上传失败");
      String document = s(map(map(result.get("data")).get("document")), "id");
      api(
          base,
          "/knowledge-bases/kb_" + uid + "/documents/" + document + "/index-tasks",
          token,
          obj());
    }
    System.out.println("已为演示账号保存10套SOP并创建索引任务");
  }

  private static Map<String, Object> api(
      String base, String path, String token, Map<String, Object> body) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
    if (token != null) b.header("Authorization", "Bearer " + token);
    return decode(
        HttpClient.newHttpClient()
            .send(
                b.POST(HttpRequest.BodyPublishers.ofString(encode(body))).build(),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }
}
