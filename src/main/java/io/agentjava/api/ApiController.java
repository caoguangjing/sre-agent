package io.agentjava.api;

import static io.agentjava.store.Json.*;

import io.agentjava.agent.*;
import io.agentjava.aiops.*;
import io.agentjava.auth.AuthService;
import io.agentjava.config.ProjectConfig;
import io.agentjava.feedback.FeedbackService;
import io.agentjava.jobs.JobRuntime;
import io.agentjava.knowledge.*;
import io.agentjava.mcp.McpService;
import io.agentjava.model.QwenProvider;
import io.agentjava.store.Store;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class ApiController {
  private final Store store;
  private final AuthService auth;
  private final ProjectConfig config;
  private final QwenProvider model;
  private final JobRuntime jobs;
  private final KnowledgeService knowledge;
  private final ChatService chat;
  private final AssetService assets;
  private final McpService mcp;
  private final DiagnosisService diagnosis;
  private final AlertService alerts;
  private final InspectionService inspection;
  private final FeedbackService feedback;
  private final RequestFilter metrics;
  private static final Logger log = LoggerFactory.getLogger(ApiController.class);

  public ApiController(
      Store store,
      AuthService auth,
      ProjectConfig config,
      QwenProvider model,
      JobRuntime jobs,
      KnowledgeService knowledge,
      ChatService chat,
      AssetService assets,
      McpService mcp,
      DiagnosisService diagnosis,
      AlertService alerts,
      InspectionService inspection,
      FeedbackService feedback,
      RequestFilter metrics) {
    this.store = store;
    this.auth = auth;
    this.config = config;
    this.model = model;
    this.jobs = jobs;
    this.knowledge = knowledge;
    this.chat = chat;
    this.assets = assets;
    this.mcp = mcp;
    this.diagnosis = diagnosis;
    this.alerts = alerts;
    this.inspection = inspection;
    this.feedback = feedback;
    this.metrics = metrics;
  }

  @RequestMapping("/**")
  public void route(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String path = req.getRequestURI(),
        method = req.getMethod(),
        owner = str(req.getAttribute("owner"));
    String[] p = path.substring(1).split("/");
    String op = method + " " + path;
    try {
      if (op.equals("GET /openapi.json")) {
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter()
            .write(
                new String(
                    ApiController.class.getResourceAsStream("/openapi.json").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
        return;
      }
      if (op.equals("GET /health")) {
        ok(req, res, obj("status", "ok", "service", "agent-java-backend", "version", "0.1.0"));
        return;
      }
      if (op.equals("GET /metrics")) {
        ok(
            req,
            res,
            obj(
                "requests",
                metrics.requests.sum(),
                "failures",
                metrics.failures.sum(),
                "averageDurationMs",
                metrics.duration.sum() * 1.0 / Math.max(1, metrics.requests.sum())));
        return;
      }
      if (op.equals("GET /ready") || op.equals("GET /config/check")) {
        String err = null;
        try {
          config.validate();
        } catch (Exception e) {
          err = config.safe(e);
        }
        var deps =
            err == null
                ? dependencies()
                : obj("configuration", obj("ok", false, "error", "配置无效，未连接外部依赖"));
        boolean good =
            err == null
                && deps.values().stream().allMatch(v -> Boolean.TRUE.equals(map(v).get("ok")));
        Object data =
            path.equals("/ready")
                ? obj("status", good ? "ready" : "not-ready", "dependencies", deps)
                : obj(
                    "configuration",
                    obj("project", obj("ok", err == null, "error", err)),
                    "dependencies",
                    deps);
        Protocol.success(req, res, good ? 200 : 503, data);
        return;
      }
      if (op.equals("GET /health/mcp")) {
        List<Map<String, Object>> tools;
        try {
          tools =
              mcp.discover(
                  obj(
                      "name",
                      "sls-health",
                      "url",
                      config.text("mcp", "slsSseUrl"),
                      "transport",
                      "sse",
                      "timeoutSeconds",
                      config.number("mcp", "timeoutSeconds", 15)));
          Protocol.success(req, res, 200, obj("ok", true, "tools", tools, "error", null));
        } catch (Exception e) {
          Protocol.success(req, res, 503, obj("ok", false, "tools", List.of(), "error", "MCP不可用"));
        }
        return;
      }
      if (op.equals("POST /auth/register")) {
        Protocol.success(req, res, 201, auth.register(body(req)));
        return;
      }
      if (op.equals("POST /auth/login")) {
        ok(req, res, auth.login(body(req)));
        return;
      }
      if (op.equals("GET /auth/me")) {
        ok(req, res, req.getAttribute("user"));
        return;
      }
      if (op.equals("POST /auth/logout")) {
        auth.logout(req.getHeader("Authorization").substring(7));
        ok(req, res, obj("revoked", true));
        return;
      }
      if (p[0].equals("background-jobs")) {
        if (p.length == 1 && method.equals("GET")) {
          ok(req, res, obj("items", jobs.list(owner)));
          return;
        }
        if (p.length == 2) {
          if (method.equals("GET")) {
            ok(req, res, jobs.get(owner, p[1]));
            return;
          }
          if (method.equals("POST")) {
            String[] action = p[1].split(":");
            if (action.length == 2) {
              ok(
                  req,
                  res,
                  action[1].equals("cancel")
                      ? jobs.cancel(owner, action[0])
                      : action[1].equals("retry") ? jobs.retry(owner, action[0]) : missing());
              return;
            }
          }
        }
      }
      if (op.equals("GET /knowledge-bases")) {
        ok(
            req,
            res,
            obj("items", List.of(obj("id", "kb_" + owner, "name", "个人知识库", "ownerUserId", owner))));
        return;
      }
      if (p[0].equals("knowledge-bases") && p.length >= 3 && p[2].equals("documents")) {
        String kb = p[1];
        knowledge.scope(owner, kb);
        if (p.length == 3) {
          if (method.equals("GET")) {
            ok(req, res, obj("items", knowledge.list(owner, kb)));
            return;
          }
          if (method.equals("POST")) {
            var f = file(req);
            String raw = req.getParameter("chunking");
            var c =
                raw == null
                    ? obj(
                        "strategy",
                        "fixed-character",
                        "maxCharacters",
                        1000,
                        "overlapCharacters",
                        100)
                    : decode(raw);
            Protocol.success(
                req,
                res,
                201,
                knowledge.upload(
                    owner,
                    kb,
                    f.getOriginalFilename(),
                    f.getContentType() == null ? "application/octet-stream" : f.getContentType(),
                    f.getBytes(),
                    c,
                    "true".equals(req.getParameter("overwrite")),
                    obj()));
            return;
          }
        }
        if (p.length >= 4) {
          String doc = p[3];
          knowledge.get(owner, kb, doc);
          if (p.length == 4) {
            if (method.equals("GET")) {
              ok(req, res, knowledge.publicDocument(knowledge.get(owner, kb, doc)));
              return;
            }
            if (method.equals("DELETE")) {
              knowledge.delete(owner, kb, doc);
              ok(req, res, obj("deleted", true, "documentId", doc));
              return;
            }
          }
          if (p.length == 5 && p[4].equals("chunk-preview") && method.equals("GET")) {
            ok(req, res, obj("preview", knowledge.preview(owner, kb, doc)));
            return;
          }
          if (p.length >= 5 && p[4].equals("index-tasks")) {
            if (p.length == 5 && method.equals("POST")) {
              Protocol.success(
                  req,
                  res,
                  202,
                  obj("task", knowledge.createIndex(owner, kb, doc, null), "scheduled", true));
              return;
            }
            if (p.length == 6) {
              String index = p[5].replace(":retry", "");
              var t = store.get("index", owner, index);
              if (!s(t, "documentId").equals(doc)) throw new ApiException("AUTH_FORBIDDEN");
              if (method.equals("GET") && !p[5].contains(":")) {
                ok(req, res, t);
                return;
              }
              if (method.equals("POST") && p[5].endsWith(":retry")) {
                if (!Set.of("failed", "cancelled").contains(s(t, "status")))
                  throw new ApiException("BUSINESS_CONFLICT");
                Protocol.success(
                    req,
                    res,
                    202,
                    obj(
                        "task",
                        knowledge.createIndex(owner, kb, doc, index),
                        "scheduled",
                        true,
                        "retriedFromTaskId",
                        index));
                return;
              }
            }
          }
        }
      }
      if (op.equals("GET /chat/configuration")) {
        ok(req, res, assets.configuration(owner));
        return;
      }
      if (op.equals("PUT /chat/configuration")) {
        ok(req, res, assets.select(owner, body(req)));
        return;
      }
      if (p.length >= 2 && p[0].equals("chat")) {
        if (p[1].equals("prompts")) {
          if (method.equals("POST") && p.length == 2) {
            Protocol.success(req, res, 201, assets.savePrompt(owner, "", body(req)));
            return;
          }
          if (p.length == 3) {
            if (method.equals("PUT")) {
              ok(req, res, assets.savePrompt(owner, p[2], body(req)));
              return;
            }
            if (method.equals("DELETE")) {
              store.delete("prompt", owner, p[2]);
              assets.configuration(owner);
              ok(req, res, obj("deleted", true, "promptId", p[2]));
              return;
            }
          }
        }
        if (p[1].equals("skills")) {
          if (method.equals("POST") && p.length == 2) {
            var f = file(req);
            Protocol.success(
                req,
                res,
                201,
                assets.skill(
                    owner,
                    f.getOriginalFilename(),
                    new String(f.getBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            return;
          }
          if (p.length == 3 && method.equals("GET")) {
            ok(req, res, assets.skillContent(owner, p[2]));
            return;
          }
          if (p.length == 3 && method.equals("DELETE")) {
            store.delete("skill", owner, p[2]);
            assets.configuration(owner);
            ok(req, res, obj("deleted", true, "skillId", p[2]));
            return;
          }
        }
        if (p[1].equals("sessions")) {
          if (p.length == 2) {
            if (method.equals("GET")) {
              ok(req, res, obj("items", chat.list(owner)));
              return;
            }
            if (method.equals("POST")) {
              Protocol.success(req, res, 201, chat.create(owner, body(req)));
              return;
            }
          }
          if (p.length >= 3) {
            String session = p[2];
            chat.raw(owner, session);
            if (p.length == 3) {
              if (method.equals("GET")) {
                ok(req, res, chat.detail(owner, session));
                return;
              }
              if (method.equals("DELETE")) {
                ok(req, res, chat.clear(owner, session, true));
                return;
              }
            }
            if (p.length == 4) {
              String action = p[3];
              if (method.equals("GET") && action.equals("tool-call-audits")) {
                ok(req, res, obj("items", store.list("audit", owner, session)));
                return;
              }
              if (method.equals("PUT") && action.equals("memory")) {
                ok(req, res, chat.memory(owner, session, s(body(req), "mode")));
                return;
              }
              if (method.equals("POST")) {
                switch (action) {
                  case "memory:compact" -> {
                    ok(req, res, chat.compact(owner, session));
                    return;
                  }
                  case "messages:clear" -> {
                    ok(req, res, chat.clear(owner, session, false));
                    return;
                  }
                  case "messages" -> {
                    var b = body(req);
                    Protocol.success(
                        req,
                        res,
                        201,
                        chat.append(
                            owner, session, s(b, "role"), s(b, "content"), map(b.get("metadata"))));
                    return;
                  }
                  case "messages:stream" -> {
                    var b = body(req);
                    if (s(b, "content").isBlank())
                      throw new ApiException("VALIDATION_MISSING_FIELD");
                    var emit = Protocol.stream(res);
                    try {
                      chat.stream(owner, session, s(b, "content"), map(b.get("metadata")), emit);
                    } catch (Exception e) {
                      emit.emit(
                          Protocol.event(
                              "chat",
                              "error",
                              obj(
                                  "error",
                                  Protocol.error(
                                      e instanceof ApiException a
                                          ? a.code
                                          : "SYSTEM_UNAVAILABLE"))));
                    }
                    return;
                  }
                }
              }
            }
          }
        }
      }
      if (p.length >= 2 && p[0].equals("mcp") && p[1].equals("connections")) {
        if (p.length == 2) {
          if (method.equals("GET")) {
            ok(req, res, obj("items", mcp.list(owner)));
            return;
          }
          if (method.equals("POST")) {
            Protocol.success(req, res, 201, mcp.save(owner, "", body(req)));
            return;
          }
        }
        if (p.length == 3) {
          if (method.equals("PUT")) {
            ok(req, res, mcp.save(owner, p[2], body(req)));
            return;
          }
          if (method.equals("DELETE")) {
            store.delete("mcp", owner, p[2]);
            ok(req, res, obj("deleted", true, "connectionId", p[2]));
            return;
          }
          if (method.equals("POST") && p[2].endsWith(":check")) {
            ok(req, res, mcp.check(owner, p[2].replace(":check", "")));
            return;
          }
        }
      }
      if (op.equals("GET /aiops/alerts/active")) {
        ok(req, res, obj("items", alerts.active()));
        return;
      }
      if (op.equals("POST /aiops/alerts/sunfire")) {
        String expected = config.text("prometheusAlerts", "sunfireCallbackToken");
        if (expected.isBlank()) throw new ApiException("BUSINESS_NOT_FOUND");
        String token = req.getParameter("token");
        if (token == null
            || !java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          throw new ApiException("AUTH_UNAUTHENTICATED");
        ok(req, res, obj("received", true, "alert", alerts.receive(body(req))));
        return;
      }
      if (p.length >= 2 && p[0].equals("aiops")) {
        if (p[1].equals("diagnostics")) {
          if (p.length == 2) {
            if (method.equals("POST")) {
              Protocol.success(req, res, 202, diagnosis.create(owner, body(req)));
              return;
            }
            if (method.equals("GET")) {
              ok(req, res, obj("items", diagnosis.list(owner)));
              return;
            }
          }
          if (p.length >= 3) {
            String task = p[2];
            if (p.length == 3 && method.equals("GET")) {
              ok(req, res, diagnosis.get(owner, task));
              return;
            }
            if (p.length == 4 && method.equals("GET") && p[3].equals("evidence-chain")) {
              ok(req, res, diagnosis.chain(owner, task));
              return;
            }
            if (p.length == 3 && method.equals("POST")) {
              if (task.endsWith(":save-to-knowledge")) {
                Protocol.success(
                    req,
                    res,
                    201,
                    diagnosis.saveLegacy(owner, task.replace(":save-to-knowledge", "")));
                return;
              }
              if (task.endsWith(":stream")) {
                task = task.replace(":stream", "");
                diagnosis.get(owner, task);
                var job = jobs.resource(owner, task);
                var emit = Protocol.stream(res);
                int after = 0;
                String last = req.getHeader("Last-Event-ID");
                if (last != null && last.startsWith(s(job, "id") + ":"))
                  try {
                    after = Integer.parseInt(last.substring(last.lastIndexOf(':') + 1));
                  } catch (NumberFormatException ignored) {
                  }
                while (true) {
                  var ev = jobs.events(owner, s(job, "id"), after);
                  for (var e : ev) {
                    emit.emit(e);
                    after = Integer.parseInt(s(e, "id").substring(s(e, "id").lastIndexOf(':') + 1));
                    if (Set.of("complete", "error").contains(s(e, "type"))) return;
                  }
                  var current = jobs.get(owner, s(job, "id"));
                  if (Set.of("succeeded", "failed", "cancelled").contains(s(current, "status"))
                      && ev.isEmpty()) {
                    emit.emit(
                        Protocol.event(
                            "aiops",
                            s(current, "status").equals("succeeded") ? "complete" : "error",
                            s(current, "status").equals("succeeded")
                                ? obj("result", obj("taskId", task))
                                : obj("error", Protocol.error("SYSTEM_UNAVAILABLE"))));
                    return;
                  }
                  try {
                    Thread.sleep(200);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              }
            }
          }
        }
        if (p[1].equals("inspection")) {
          if (p.length == 3 && p[2].equals("settings")) {
            if (method.equals("GET")) {
              ok(req, res, inspection.settings());
              return;
            }
            if (method.equals("PUT")) {
              ok(req, res, inspection.updateSettings(body(req)));
              return;
            }
          }
          if (p.length == 3 && p[2].equals("findings") && method.equals("GET")) {
            ok(req, res, obj("items", inspection.findings(owner)));
            return;
          }
        }
        if (p[1].equals("diagnostic-cases") && method.equals("GET")) {
          if (p.length == 2) ok(req, res, obj("items", diagnosis.cases(owner)));
          else if (p.length == 3) ok(req, res, store.get("case", owner, p[2]));
          else throw new ApiException("BUSINESS_NOT_FOUND");
          return;
        }
      }
      if (p[0].equals("feedback")) {
        if (p.length == 1) {
          if (method.equals("POST")) {
            Protocol.success(req, res, 201, feedback.upsert(owner, body(req)));
            return;
          }
          if (method.equals("GET")) {
            ok(
                req,
                res,
                obj(
                    "items",
                    feedback.list(
                        owner,
                        str(req.getParameter("targetType")),
                        str(req.getParameter("targetId")))));
            return;
          }
        }
        if (p.length == 2) {
          if (method.equals("GET")) {
            ok(req, res, store.get("feedback", owner, p[1]));
            return;
          }
          if (method.equals("DELETE")) {
            store.delete("feedback", owner, p[1]);
            ok(req, res, obj("deleted", true, "feedbackId", p[1]));
            return;
          }
        }
      }
      throw new ApiException("BUSINESS_NOT_FOUND");
    } catch (ApiException e) {
      if (!res.isCommitted()) Protocol.failure(req, res, e.code);
    } catch (IllegalArgumentException e) {
      if (!res.isCommitted()) Protocol.failure(req, res, "VALIDATION_INVALID_ARGUMENT");
    } catch (Exception e) {
      log.error("unhandled route error: {} {}", method, path, e);
      if (!res.isCommitted()) Protocol.failure(req, res, "SYSTEM_UNAVAILABLE");
    }
  }

  // Spring Boot's nested-jar service providers require the application context classloader.
  private final ExecutorService dependencyExecutor =
      Executors.newFixedThreadPool(
          4,
          operation -> {
            Thread thread = new Thread(operation, "dependency-check");
            thread.setContextClassLoader(ApiController.class.getClassLoader());
            thread.setDaemon(true);
            return thread;
          });

  @jakarta.annotation.PreDestroy
  public void stopDependencyChecks() {
    dependencyExecutor.shutdownNow();
  }

  private Map<String, Object> dependencies() {
    var checks = new LinkedHashMap<String, Callable<Object>>();
    checks.put("sqlite", () -> store.db.queryForObject("SELECT 1", Integer.class));
    checks.put(
        "milvus",
        () -> {
          knowledge.vectors.health();
          return true;
        });
    checks.put("llm", () -> model.answer("仅回复OK", "健康检查"));
    checks.put(
        "mcp",
        () ->
            mcp.discover(
                obj(
                    "name",
                    "health",
                    "url",
                    config.text("mcp", "slsSseUrl"),
                    "transport",
                    "sse",
                    "timeoutSeconds",
                    config.number("mcp", "timeoutSeconds", 15),
                    "retries",
                    config.number("mcp", "retries", 1))));
    Map<String, Object> deps = new ConcurrentHashMap<>();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    checks.forEach(
        (name, check) ->
            futures.add(
                CompletableFuture.runAsync(
                    () -> {
                      long start = System.nanoTime();
                      var value = obj("ok", true, "latencyMs", 0, "error", null);
                      try {
                        check.call();
                      } catch (Exception e) {
                        value.putAll(
                            obj(
                                "ok",
                                false,
                                "error",
                                "依赖不可用",
                                "failureType",
                                e.getClass().getSimpleName()));
                      }
                      value.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                      if (name.equals("llm"))
                        value.putAll(
                            obj(
                                "provider",
                                "qwen-openai",
                                "model",
                                config.text("llm", "chatModel"),
                                "baseUrl",
                                config.text("llm", "baseUrl")));
                      deps.put(name, value);
                    },
                    dependencyExecutor)));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    for (int i = 0; i < futures.size(); i++)
      try {
        futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (Exception e) {
        String name = new ArrayList<>(checks.keySet()).get(i);
        deps.put(name, obj("ok", false, "error", "依赖检查超时", "latencyMs", 25000));
      }
    return deps;
  }

  private static Map<String, Object> body(HttpServletRequest req) throws IOException {
    String raw =
        new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    return raw.isBlank() ? obj() : decode(raw);
  }

  private static MultipartFile file(HttpServletRequest req) {
    if (!(req instanceof MultipartHttpServletRequest multi) || multi.getFile("file") == null)
      throw new ApiException("VALIDATION_MISSING_FIELD");
    return multi.getFile("file");
  }

  private static Object missing() {
    throw new ApiException("BUSINESS_NOT_FOUND");
  }

  private static void ok(HttpServletRequest req, HttpServletResponse res, Object data)
      throws IOException {
    Protocol.success(req, res, 200, data);
  }
}
