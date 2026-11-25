package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.auth.AuthService;
import io.agentjava.config.ProjectConfig;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AlertService {
  private final ProjectConfig config;
  private final SunfireAlertStore sunfireAlerts;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public AlertService(ProjectConfig config, SunfireAlertStore sunfireAlerts) {
    this.config = config;
    this.sunfireAlerts = sunfireAlerts;
  }

  public Map<String, Object> receive(Map<String, Object> payload) {
    return sunfireAlerts.save(payload);
  }

  public List<Map<String, Object>> active() {
    var out = new ArrayList<Map<String, Object>>();
    boolean failed = false;
    out.addAll(sunfireAlerts.active());
    for (var source : maps(config.section("prometheusAlerts").get("sources"))) {
      if (s(source, "alertsApi").isBlank()) continue;
      try {
        var b =
            HttpRequest.newBuilder(URI.create(s(source, "alertsApi")))
                .timeout(
                    Duration.ofSeconds(config.number("prometheusAlerts", "timeoutSeconds", 5)));
        if (!s(source, "username").isBlank())
          b.header(
              "Authorization",
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString(
                          (s(source, "username") + ":" + s(source, "password"))
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var r = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception();
        Object parsed = io.agentjava.store.Json.MAPPER.readValue(r.body(), Object.class);
        List<Map<String, Object>> alerts;
        if (s(source, "type").equals("prometheus-v1")) {
          var p = map(parsed);
          if (!s(p, "status").equals("success")) throw new Exception();
          alerts = maps(map(p.get("data")).get("alerts"));
        } else alerts = maps(parsed);
        for (var alert : alerts) {
          String state =
              s(source, "type").equals("prometheus-v1")
                  ? s(alert, "state")
                  : s(map(alert.get("status")), "state");
          if (!Set.of("firing", "active").contains(state)) continue;
          var labels = map(alert.get("labels"));
          var annotations = map(alert.get("annotations"));
          String start = s(alert, "startsAt");
          if (start.isBlank()) start = s(alert, "activeAt");
          String aid =
              AuthService.digest(s(source, "id") + encode(labels) + start).substring(0, 24);
          out.add(
              obj(
                  "id",
                  aid,
                  "source",
                  s(source, "id"),
                  "alertName",
                  s(labels, "alertname"),
                  "service",
                  s(labels, "service"),
                  "severity",
                  s(labels, "severity"),
                  "status",
                  state,
                  "startsAt",
                  start,
                  "summary",
                  s(annotations, "summary"),
                  "labels",
                  labels,
                  "annotations",
                  annotations,
                  "context",
                  alert));
        }
      } catch (Exception e) {
        failed = true;
      }
    }
    if (failed && out.isEmpty()) throw new ApiException("SYSTEM_UNAVAILABLE");
    return out;
  }
}
