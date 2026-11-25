package io.agentjava.agent;

import static io.agentjava.store.Json.*;

import io.agentjava.api.Protocol;
import io.agentjava.store.Store;
import io.agentscope.core.message.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.*;
import java.util.*;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class AuditedTool extends ToolBase {
  private final Store store;
  private final String owner, parent, channel;
  private final Function<Map<String, Object>, String> operation;
  private final Protocol.Emitter emit;

  public AuditedTool(
      Store store,
      String owner,
      String parent,
      String channel,
      String name,
      String description,
      Map<String, Object> schema,
      Function<Map<String, Object>, String> operation,
      Protocol.Emitter emit) {
    super(
        ToolBase.builder()
            .name(name)
            .description(description)
            .inputSchema(schema)
            .readOnly(true)
            .concurrencySafe(false));
    this.store = store;
    this.owner = owner;
    this.parent = parent;
    this.channel = channel;
    this.operation = operation;
    this.emit = emit;
  }

  @Override
  public Mono<PermissionDecision> checkPermissions(
      Map<String, Object> input, PermissionContextState context) {
    return Mono.just(PermissionDecision.allow("用户已启用的工具"));
  }

  @Override
  public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    return Mono.fromCallable(
            () -> {
              String tid = param.getToolUseBlock().getId();
              long start = System.nanoTime();
              var audit =
                  obj(
                      "id",
                      tid,
                      "sessionId",
                      channel.equals("chat") ? parent : null,
                      "diagnosticTaskId",
                      channel.equals("aiops") ? parent : null,
                      "toolName",
                      getName(),
                      "status",
                      "started",
                      "arguments",
                      redact(param.getInput()),
                      "resultSummary",
                      null,
                      "errorMessage",
                      null,
                      "startedAt",
                      now(),
                      "completedAt",
                      null,
                      "durationMs",
                      null,
                      "createdAt",
                      now());
              store.put("audit", owner, parent, audit);
              emit.emit(
                  Protocol.event(
                      channel,
                      "tool.call",
                      obj(
                          "toolCall",
                          obj(
                              "id",
                              tid,
                              "name",
                              getName(),
                              "status",
                              "started",
                              "input",
                              redact(param.getInput())))));
              String output;
              boolean failed = false;
              try {
                output = operation.apply(param.getInput());
                audit.put("status", "completed");
                audit.put("resultSummary", output.substring(0, Math.min(2000, output.length())));
              } catch (Exception e) {
                output = "工具执行失败";
                failed = true;
                audit.put("status", "failed");
                audit.put("errorMessage", "工具依赖不可用或参数无效");
              }
              audit.put("completedAt", now());
              audit.put("durationMs", (System.nanoTime() - start) / 1_000_000);
              store.put("audit", owner, parent, audit);
              emit.emit(
                  Protocol.event(
                      channel,
                      "tool.call",
                      obj(
                          "toolCall",
                          obj(
                              "id",
                              tid,
                              "name",
                              getName(),
                              "status",
                              s(audit, "status"),
                              "output",
                              failed ? output : audit.get("resultSummary")))));
              return failed
                  ? ToolResultBlock.error(tid, output)
                  : ToolResultBlock.text(output).withIdAndName(tid, getName());
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  public static Object redact(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach(
          (k, v) ->
              out.put(
                  str(k),
                  str(k)
                          .toLowerCase(Locale.ROOT)
                          .matches(".*(password|token|secret|api.?key|authorization).* ".trim())
                      ? "[redacted]"
                      : redact(v)));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(AuditedTool::redact).toList();
    return value;
  }

  public static Map<String, Object> schema(String param, String desc) {
    return obj(
        "type",
        "object",
        "properties",
        obj(param, obj("type", "string", "description", desc)),
        "required",
        List.of(param));
  }
}
