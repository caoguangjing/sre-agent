package io.agentjava.mcp;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.config.ProjectConfig;
import io.agentjava.store.Store;
import io.agentscope.core.tool.mcp.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class McpService {
  private final Store store;
  private final ProjectConfig config;

  public McpService(Store store, ProjectConfig config) {
    this.store = store;
    this.config = config;
  }

  public static class ToolFailure extends RuntimeException {
    public final boolean retryable;

    ToolFailure(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }
  }

  public int toolTimeoutSeconds(Map<String, Object> connection, String tool) {
    int connectionTimeout = Math.max(1, n(connection.get("timeoutSeconds")));
    int toolTimeout = n(map(config.section("mcp").get("toolTimeoutSeconds")).get(tool));
    return Math.min(300, Math.max(connectionTimeout, toolTimeout));
  }

  private static String toolError(Object result) {
    var messages = new ArrayList<String>();
    for (var block : maps(decode(encode(result)).get("content"))) {
      String text = s(block, "text");
      try {
        var error = decode(text);
        if (!s(error, "message").isBlank()) text = s(error, "message");
      } catch (IllegalArgumentException ignored) {
      }
      if (!text.isBlank()) messages.add(text);
    }
    String message = String.join("；", messages);
    if (message.isBlank()) message = "工具未提供错误详情";
    return message.substring(0, Math.min(2000, message.length()));
  }

  private static boolean timedOut(Throwable error) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause())
      if (cause instanceof java.util.concurrent.TimeoutException) return true;
    return false;
  }

  public List<Map<String, Object>> list(String owner) {
    return store.list("mcp", owner, "").stream().map(McpService::masked).toList();
  }

  public Map<String, Object> save(String owner, String id, Map<String, Object> req) {
    String url = s(req, "url"), transport = s(req, "transport");
    try {
      URI u = URI.create(url);
      if (!Set.of("http", "https").contains(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null) throw new Exception();
    } catch (Exception e) {
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    }
    if (s(req, "name").isBlank()
        || !Set.of("sse", "streamable_http").contains(transport)
        || n(req.get("timeoutSeconds")) < 1
        || n(req.get("timeoutSeconds")) > 300
        || n(req.get("retries")) < 0
        || n(req.get("retries")) > 5) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    if (!id.isEmpty()) store.get("mcp", owner, id);
    if (req.get("headers") != null && !(req.get("headers") instanceof Map))
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var v = new LinkedHashMap<>(req);
    if (req.get("headers") != null) v.put("headers", resolvedHeaders(owner, id, req));
    else if (!id.isEmpty()) v.put("headers", map(store.get("mcp", owner, id).get("headers")));
    v.putAll(
        obj(
            "id",
            id.isEmpty() ? id("mcp") : id,
            "lastCheck",
            null,
            "createdAt",
            id.isEmpty() ? now() : store.get("mcp", owner, id).get("createdAt"),
            "updatedAt",
            now()));
    return masked(store.put("mcp", owner, "", v));
  }

  public McpClientWrapper connect(Map<String, Object> c) {
    var b =
        McpClientBuilder.create(s(c, "name"))
            .timeout(Duration.ofSeconds(Math.max(1, n(c.get("timeoutSeconds")))))
            .initializationTimeout(Duration.ofSeconds(Math.max(1, n(c.get("timeoutSeconds")))));
    var headers = new LinkedHashMap<String, String>();
    for (var e : map(c.get("headers")).entrySet()) headers.put(e.getKey(), str(e.getValue()));
    // AgentScope headers() silently drops when no transport is selected yet, so it must follow the transport call.
    if (s(c, "transport").equals("sse")) b.sseTransport(s(c, "url"));
    else b.streamableHttpTransport(s(c, "url"));
    if (!headers.isEmpty()) b.headers(headers);
    var client = b.buildSync();
    try {
      client.initialize().block(Duration.ofSeconds(Math.max(1, n(c.get("timeoutSeconds")))));
      return client;
    } catch (RuntimeException e) {
      client.close();
      throw e;
    }
  }

  public List<Map<String, Object>> discover(Map<String, Object> c) {
    for (int attempt = 0; ; attempt++) {
      try (var client = connect(c)) {
        return client.listTools().block().stream()
            .map(
                t ->
                    obj(
                        "name",
                        t.name(),
                        "description",
                        t.description() == null ? "" : t.description(),
                        "inputSchema",
                        decode(encode(t.inputSchema())),
                        "serverName",
                        s(c, "name")))
            .toList();

      } catch (Exception error) {
        if (attempt >= n(c.get("retries")) || Thread.currentThread().isInterrupted())
          throw new IllegalStateException("MCP工具发现失败", error);
      }
    }
  }

  public Map<String, Object> check(String owner, String id) {
    var c = store.get("mcp", owner, id);
    List<Map<String, Object>> tools = List.of();
    String error = null;
    try {
      tools = discover(c);
    } catch (Exception e) {
      error = "MCP 连接失败，请检查地址与服务";
    }
    c.put(
        "lastCheck",
        obj(
            "ok",
            error == null,
            "toolCount",
            tools.size(),
            "tools",
            tools,
            "error",
            error,
            "checkedAt",
            now()));
    store.put("mcp", owner, "", c);
    return obj("connection", masked(c), "tools", tools);
  }

  private Map<String, Object> resolvedHeaders(String owner, String id, Map<String, Object> req) {
    var incoming = map(req.get("headers"));
    for (var e : incoming.entrySet()) {
      if (!e.getKey().matches("[A-Za-z0-9-]{1,64}")
          || !(e.getKey().equals("Authorization") || e.getKey().startsWith("X-"))
          || !(e.getValue() instanceof String value)
          || value.isBlank()
          || value.length() > 2048) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    }
    var existing =
        id.isEmpty() ? Map.<String, Object>of() : map(store.get("mcp", owner, id).get("headers"));
    var resolved = new LinkedHashMap<String, Object>();
    for (var e : incoming.entrySet()) {
      String value = String.valueOf(e.getValue());
      String current = s(existing, e.getKey());
      // 掩码值回传时还原为存量真实值，避免表单回显把掩码串存成新凭证
      if (!current.isEmpty() && value.equals(maskValue(current))) value = current;
      resolved.put(e.getKey(), value);
    }
    return resolved;
  }

  private static Map<String, Object> masked(Map<String, Object> connection) {
    var v = new LinkedHashMap<>(connection);
    var headers = map(v.get("headers"));
    if (!headers.isEmpty()) {
      var out = new LinkedHashMap<String, Object>();
      for (var e : headers.entrySet()) out.put(e.getKey(), maskValue(str(e.getValue())));
      v.put("headers", out);
    }
    return v;
  }

  private static String maskValue(String value) {
    return value.length() <= 8
        ? "[redacted]"
        : value.substring(0, 4) + "***" + value.substring(value.length() - 4);
  }

  private static String redacted(Map<String, Object> c, String text) {
    for (var e : map(c.get("headers")).entrySet()) {
      String v = str(e.getValue());
      if (!v.isEmpty()) text = text.replace(v, "[redacted]");
    }
    return text;
  }

  public Map<String, Map<String, Object>> tools(String owner) {
    var out = new LinkedHashMap<String, Map<String, Object>>();
    // 必须用未掩码的存量记录：掩码后的 Authorization 无法通过服务端鉴权；
    // 用户显式注册的连接优先，内置连接（mcp.builtin）只补缺
    for (var c : store.list("mcp", owner, "")) {
      if (!Boolean.TRUE.equals(c.get("enabled"))) continue;
      mergeTools(out, List.of(c), false);
    }
    mergeTools(out, builtinConnections(), true);
    return out;
  }

  private List<Map<String, Object>> builtinConnections() {
    var v = config.section("mcp").get("builtin");
    if (!(v instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(e -> e instanceof Map)
        .map(e -> map(e))
        .filter(c -> Boolean.TRUE.equals(c.get("enabled")))
        .toList();
  }

  private void mergeTools(
      Map<String, Map<String, Object>> out, List<Map<String, Object>> connections, boolean tolerateFailure) {
    for (var c : connections) {
      List<Map<String, Object>> discovered;
      try {
        discovered = discover(c);
      } catch (Exception e) {
        if (!tolerateFailure) throw e;
        continue; // 内置连接不可用不阻塞已注册工具
      }
      for (var t : discovered) {
        String name = s(t, "name");
        if (out.containsKey(name)
            || Set.of("knowledge_retrieval", "get_current_time", "load_skill").contains(name)) continue;
        var v = new LinkedHashMap<>(t);
        v.put("connection", c);
        out.put(name, v);
      }
    }
  }

  public String call(Map<String, Object> tool, Map<String, Object> args) {
    var c = new LinkedHashMap<>(map(tool.get("connection")));
    int timeout = toolTimeoutSeconds(c, s(tool, "name"));
    c.put("timeoutSeconds", timeout);
    for (int attempt = 0; ; attempt++) {
      try (var client = connect(c)) {
        var result = client.callTool(s(tool, "name"), args).block();
        if (Boolean.TRUE.equals(result.isError())) {
          String error = toolError(result);
          boolean retryable = !error.matches("(?is).*(QueryError|SyntaxError|InvalidParameter|UnknownTool).*");
          throw new ToolFailure("MCP 工具返回错误：" + error, retryable, null);
        }
        return encode(result);
      } catch (Exception e) {
        if (e instanceof java.util.concurrent.CancellationException) throw e;
        if (e instanceof ToolFailure failure && !failure.retryable) throw failure;
        if (attempt >= n(c.get("retries")) || Thread.currentThread().isInterrupted())
          throw timedOut(e)
              ? new ToolFailure(s(tool, "name") + " 调用超时（" + timeout + " 秒），请检查MCP服务或提高该工具超时配置", true, e)
              : new ToolFailure("MCP 工具执行失败：" + redacted(c, config.safe(e)), true, e);
      }
    }
  }
}
