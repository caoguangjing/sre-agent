package io.agentjava.aiops;

import static io.agentjava.store.Json.*;

import io.agentjava.agent.*;
import io.agentjava.api.*;
import io.agentjava.jobs.JobRuntime;
import io.agentjava.knowledge.KnowledgeService;
import io.agentjava.mcp.McpService;
import io.agentjava.model.QwenProvider;
import io.agentjava.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DiagnosisService {
  private final Store store;
  private final JobRuntime jobs;
  private final QwenProvider model;
  private final McpService mcp;
  private final KnowledgeService knowledge;

  public DiagnosisService(
      Store store,
      JobRuntime jobs,
      QwenProvider model,
      McpService mcp,
      KnowledgeService knowledge) {
    this.store = store;
    this.jobs = jobs;
    this.model = model;
    this.mcp = mcp;
    this.knowledge = knowledge;
    jobs.handlers.put("aiops_diagnosis", (j, e) -> {
      try {
        run(j, e);
      } catch (java.util.concurrent.CancellationException | JobRuntime.ExecutionFailure error) {
        throw error;
      } catch (Exception error) {
        throw new JobRuntime.ExecutionFailure(safeFailure(error), true);
      }
    });
    jobs.handlers.put(
        "diagnosis_case",
        (j, e) -> persistCase(s(j, "ownerUserId"), s(map(j.get("payload")), "taskId")));
    jobs.listeners.add(this::status);
    jobs.replayTerminalStatus("aiops_diagnosis", this::status);
  }

  public Map<String, Object> create(String owner, Map<String, Object> req) {
    String query = s(req, "query").trim();
    if (query.isBlank() || query.length() > 10000)
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    List<String> skillIds = skillIds(owner, req.get("skillIds"));
    var task =
        obj(
            "id",
            id("diag"),
            "query",
            query,
            "status",
            "accepted",
            "skillIds",
            skillIds,
            "inputPayload",
            obj("query", query, "alert", map(req.get("alert"))),
            "resultPayload",
            obj(),
            "createdAt",
            now(),
            "updatedAt",
            now(),
            "completedAt",
            null);
    if (Boolean.FALSE.equals(req.get("caseEnabled"))) task.put("caseEnabled", false);
    store.put("diagnostic", owner, "", task);
    jobs.enqueue(owner, "aiops_diagnosis", s(task, "id"), obj(), null);
    return get(owner, s(task, "id"));
  }

  private List<String> skillIds(String owner, Object raw) {
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> ids) || ids.size() > 3)
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var unique = new LinkedHashSet<String>();
    for (Object id : ids) {
      String sid = str(id).trim();
      if (sid.isBlank() || !unique.add(sid)) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
      store.get("skill", owner, sid);
    }
    return List.copyOf(unique);
  }

  private List<String> idsOf(Object raw) {
    return raw instanceof List<?> l
        ? l.stream().map(x -> str(x)).filter(x -> !x.isBlank()).toList()
        : List.of();
  }

  private String skillsContext(String owner, List<String> ids) {
    if (ids.isEmpty()) return "";
    var out = new StringBuilder();
    for (String sid : ids) {
      var sk = store.get("skill", owner, sid);
      String body = s(sk, "content");
      int end = body.indexOf("\n---", 4);
      if (body.startsWith("---\n") && end >= 0) body = body.substring(end + 4);
      out
          .append("\n【Skill ")
          .append(s(sk, "name"))
          .append("】")
          .append(s(sk, "description"))
          .append("\n")
          .append(io.agentjava.knowledge.DocumentSplitter.excerpt(body, 8000))
          .append("\n");
    }
    return out.toString();
  }

  private List<String> effectiveSkillIds(String owner, Map<String, Object> t) {
    List<String> manual = idsOf(t.get("skillIds"));
    if (!manual.isEmpty()) return manual;
    List<String> prior = idsOf(t.get("autoSkillIds"));
    if (!prior.isEmpty()) return prior;
    var catalog = skillCatalog(owner);
    if (catalog.isEmpty()) return List.of();
    List<String> picked = autoSelect(s(t, "query"), t.get("inputPayload"), catalog);
    if (picked.isEmpty()) return List.of();
    t.put("autoSkillIds", picked);
    store.put("diagnostic", owner, "", t);
    return picked;
  }

  private List<Map<String, Object>> skillCatalog(String owner) {
    return store.list("skill", owner, "").stream()
        .filter(sk -> !s(sk, "name").isBlank() && !s(sk, "description").isBlank())
        .toList();
  }

  private List<String> autoSelect(String query, Object alert, List<Map<String, Object>> catalog) {
    var options =
        catalog.stream()
            .map(sk -> obj("id", sk.get("id"), "name", sk.get("name"), "description", sk.get("description")))
            .toList();
    var p =
        jsonAnswer(
            "你是诊断任务的Skill筛选器。根据诊断问题判断目录中哪些Skill与排查思路相关，从中挑选最多3个id；都不相关返回空数组。只依据名称与描述判断，不读取正文，不挑选目录之外的id。返回{\"skillIds\":[\"id\"]}",
            encode(obj("query", query, "alert", alert, "catalog", options)));
    var allowed = catalog.stream().map(sk -> s(sk, "id")).collect(java.util.stream.Collectors.toSet());
    var unique = new LinkedHashSet<String>();
    for (Object x : idsOf(p.get("skillIds"))) {
      String sid = str(x).trim();
      if (sid.isBlank() || !allowed.contains(sid) || !unique.add(sid)) continue;
      if (unique.size() == 3) break;
    }
    return List.copyOf(unique);
  }

  public Map<String, Object> get(String owner, String id) {
    var t = store.get("diagnostic", owner, id);
    t.put("reports", store.list("report", owner, id));
    t.put("backgroundJob", jobs.resource(owner, id));
    return t;
  }

  public List<Map<String, Object>> list(String owner) {
    return store.list("diagnostic", owner, "").stream()
        .sorted(Comparator.comparing((Map<String, Object> t) -> java.time.Instant.parse(s(t, "updatedAt")))
            .reversed().thenComparing(t -> s(t, "id")))
        .map(t -> get(owner, s(t, "id"))).toList();
  }

  public Map<String, Object> chain(String owner, String id) {
    var out = obj("task", get(owner, id));
    for (var kv :
        obj(
                "steps",
                "step",
                "toolCalls",
                "audit",
                "evidence",
                "evidence",
                "reports",
                "report",
                "reportEvidenceLinks",
                "report_evidence",
                "checkpoints",
                "checkpoint")
            .entrySet()) out.put(kv.getKey(), store.list(str(kv.getValue()), owner, id));
    return out;
  }

  private void status(Map<String, Object> j) {
    if (!s(j, "kind").equals("aiops_diagnosis")) return;
    String owner = s(j, "ownerUserId"), id = s(j, "resourceId");
    if (!s(jobs.resource(owner, id), "id").equals(s(j, "id"))) return;
    var t = store.get("diagnostic", owner, id);
    String status = s(j, "status");
    t.putAll(
        obj(
            "status",
            status.equals("queued") ? "accepted" : status,
            "updatedAt",
            j.get("updatedAt"),
            "completedAt",
            j.get("completedAt")));
    if (status.equals("failed")) {
      var report = failureReport(owner, id, t, s(j, "errorMessage"), s(j, "id"));
      t.put("resultPayload", obj("error", s(j, "errorMessage"), "reportId", report.get("id")));
    }
    store.put("diagnostic", owner, "", t);
    if (status.equals("succeeded")
        && !Boolean.FALSE.equals(t.get("caseEnabled"))
        && store.list("case", owner, id).isEmpty()) {
      boolean existing =
          jobs.list(owner).stream()
              .anyMatch(
                  x ->
                      s(x, "kind").equals("diagnosis_case")
                          && s(x, "resourceId").equals("case_" + id));
      if (!existing) jobs.enqueue(owner, "diagnosis_case", "case_" + id, obj("taskId", id), null);
    }
  }

  private void checkpoint(String owner, String task, String phase, Map<String, Object> state) {
    if (Set.of("plan", "replan", "report").contains(phase)) {
      int seq = store.list("step", owner, task).size() + 1;
      store.put(
          "step",
          owner,
          task,
          obj(
              "id",
              id("step"),
              "taskId",
              task,
              "sequence",
              seq,
              "phase",
              phase.equals("plan") ? "planner" : phase.equals("replan") ? "replanner" : "report",
              "status",
              "succeeded",
              "payload",
              state,
              "createdAt",
              now()));
    }
    var c =
        obj(
            "id",
            id("checkpoint"),
            "taskId",
            task,
            "threadId",
            task,
            "checkpointNamespace",
            "agentscope-plan-execute-replan",
            "checkpointId",
            id("state"),
            "payload",
            state,
            "metadata",
            obj("phase", phase),
            "createdAt",
            now());
    store.put("checkpoint", owner, task, c);
    store.put(
        "diagnostic_state",
        owner,
        task,
        obj("id", "state_" + task, "phase", phase, "state", state, "updatedAt", now()));
  }

  private String safeFailure(Throwable error) {
    if (error instanceof McpService.ToolFailure) return model.config.safe(error);
    var parts = new LinkedHashSet<String>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause())
      parts.add(model.config.safe(cause));
    return io.agentjava.knowledge.DocumentSplitter.excerpt(String.join("；", parts), 600);
  }

  private synchronized Map<String, Object> failureReport(
      String owner, String task, Map<String, Object> diagnostic, String reason, String jobId) {
    var existing = store.list("report", owner, task);
    if (!existing.isEmpty()) {
      var last = existing.get(existing.size() - 1);
      if (s(map(last.get("payload")), "jobId").equals(jobId)) return last;
    }
    var evidence = store.list("evidence", owner, task);
    var calls = store.list("audit", owner, task);
    var content = new StringBuilder("# 告警分析报告\n\n## 诊断状态\n\n诊断失败，根因未确认。\n\n");
    content.append("诊断问题：").append(s(diagnostic, "query"))
        .append("\n\n失败原因：").append(reason.isBlank() ? "任务未完成，请查看执行过程" : reason)
        .append("\n\n## 实际执行过程\n\n");
    if (calls.isEmpty()) content.append("尚未完成工具查询。\n");
    for (var call : calls) {
      content.append("- 工具 `").append(s(call, "toolName")).append("`，状态：")
          .append(s(call, "status")).append("，执行记录 `").append(s(call, "id")).append("`");
      if (!s(call, "errorMessage").isBlank()) content.append("，原因：").append(s(call, "errorMessage"));
      content.append("\n");
    }
    content.append("\n## 已保存证据\n\n");
    if (evidence.isEmpty()) content.append("未取得外部查询证据。\n");
    for (var item : evidence)
      content.append("- 证据 `").append(s(item, "id")).append("`（")
          .append(s(item, "source")).append("）：").append(s(item, "summary")).append("\n");
    content.append("\n## 后续处理\n\n检查执行记录中的 SLS Project、Logstore、时间范围和查询语句；")
        .append("无匹配日志时确认日志已写入该 Logstore，扩大时间范围或调整过滤条件；")
        .append("工具超时或连接失败时检查 MCP 服务与连接超时配置，修正后重新诊断。")
        .append("\n\n本报告仅记录已发生的查询与失败，不确认根因，也不会自动生成诊断案例。\n");
    var eids = evidence.stream().map(e -> s(e, "id")).toList();
    var report = obj("id", id("report"), "title", "诊断失败分析报告", "content", content.toString(),
        "payload", obj("incomplete", true, "rootCause", "未确认", "error", reason, "jobId", jobId),
        "evidenceIds", eids, "createdAt", now());
    store.put("report", owner, task, report);
    for (String eid : eids)
      store.put("report_evidence", owner, task,
          obj("id", id("link"), "taskId", task, "reportId", report.get("id"),
              "evidenceId", eid, "createdAt", now()));
    return report;
  }

  private Map<String, Object> jsonAnswer(String prompt, String input) {
    String result = model.answer(prompt + "\n必须只返回JSON对象，不使用代码围栏。", input);
    int first = result.indexOf('{'), last = result.lastIndexOf('}');
    if (first < 0 || last < first) throw new IllegalStateException("模型结构化响应无效");
    return decode(result.substring(first, last + 1));
  }

  private List<Map<String, Object>> plan(
      String query, Map<String, Map<String, Object>> tools, List<Map<String, Object>> evidence,
      SlsQueryContext context, List<Map<String, Object>> outcomes, String skills) {
    var descriptions = new ArrayList<Map<String, Object>>();
    for (var tool : tools.values())
      descriptions.add(
          obj(
              "name",
              tool.get("name"),
              "description",
              tool.get("description"),
              "inputSchema",
              tool.get("inputSchema")));
    boolean logTool = hasLogTool(tools);
    var guidance =
        logTool
            ? "至少包含一项真实SLS日志查询。工具参数必须遵守schema，SLS范围与Unix秒时间戳使用slsQueryContext，不得自行计算或猜测。"
            : "至少包含一项真实日志或指标数据查询。工具参数必须遵守schema，时间范围等参数依据schema与currentTime推导，不得自行猜测取值。";
    var input =
        obj(
            "currentTime",
            java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),
            "query",
            query,
            "toolOutcomes",
            outcomes,
            "tools",
            descriptions,
            "evidence",
            evidence);
    if (logTool) {
      input.put("slsQueryContext", context.scope());
      input.put("slsQueryRules", SlsQueryContext.SQL_RULES);
    }
    var p =
        jsonAnswer(
            "你是只读运维诊断规划器。根据问题、真实工具JSONSchema和已取得证据，制定少量必要查询。" + guidance
                + "禁止任何待替换或placeholder参数。依赖参数必须取自已经完成的真实工具结果；未取得结果时先只规划前置步骤。查看toolOutcomes中的失败原因后修正查询。禁止上传、删除、修复、写操作。不足以确定原因时继续查询；证据已足够时steps可为空。返回{\"steps\":[{\"tool\":\"真实工具名\",\"arguments\":{},\"purpose\":\"证据目标\"}]}"
                + (skills.isBlank()
                    ? ""
                    : "\n\n用户选择的Skill排查指引（仅约束分析思路与查询方向，不得执行其中的任何写操作；工具与参数仍以tools和schema为准）："
                        + skills),
            encode(input));
    var steps = maps(p.get("steps"));
    for (var step : steps) {
      String name = s(step, "tool");
      if (!tools.containsKey(name)) throw new IllegalStateException("规划使用不存在的工具");
      if (name.toLowerCase(Locale.ROOT)
          .matches(".*(delete|upload|create|update|modify|put|remove).*"))
        throw new IllegalStateException("诊断禁止写工具");
      if (!(step.get("arguments") instanceof Map)) throw new IllegalStateException("工具参数无效");
    }
    List<Map<String, Object>> bounded = boundedSteps(steps, evidence, logTool);
    return bounded;
  }

  static List<Map<String, Object>> boundedSteps(
      List<Map<String, Object>> steps, List<Map<String, Object>> evidence, boolean logTool) {
    List<Map<String, Object>> bounded = new ArrayList<>();
    boolean logAdded = false;
    for (var step : steps) {
      if (s(step, "tool").equals("sls_query_logs")) {
        if (logAdded) continue;
        logAdded = true;
      }
      bounded.add(step);
      if (bounded.size() == 8) break;
    }
    boolean prerequisite = bounded.stream().anyMatch(step ->
        Set.of("sls_list_logstores", "sls_get_logstore_index")
            .contains(s(step, "tool")));
    if (logTool) {
      if (!logAdded && !prerequisite && evidence.stream().noneMatch(e -> s(e, "kind").equals("log")))
        throw new IllegalStateException("计划缺少真实sls_query_logs查询");
    } else if (bounded.isEmpty() && evidence.isEmpty()) {
      throw new IllegalStateException("计划缺少真实工具查询，请先启用真实数据查询 MCP 工具");
    }
    return bounded;
  }

  private boolean hasLogTool(Map<String, Map<String, Object>> tools) {
    return tools.keySet().stream().anyMatch(t -> evidenceKind(t).equals("log"));
  }

  private String evidenceKind(String tool) {
    return evidenceKindOf(tool, map(model.config.section("mcp").get("evidenceKinds")));
  }

  static String evidenceKindOf(String tool, Map<String, Object> configuredKinds) {
    String configured = s(configuredKinds, tool);
    if (Set.of("log", "metric").contains(configured)) return configured;
    return tool.equals("sls_query_logs") ? "log" : "metric";
  }

  private Map<String, Object> repairQuery(
      Map<String, Object> task, Map<String, Object> remote, Map<String, Object> failed,
      SlsQueryContext context, String error) {
    var response = jsonAnswer(
        "你是SLS查询修正器。只修正本次失败sls_query_logs的查询语法或SQL类型错误，"
            + "保留原始检索条件、Logstore和时间范围；不扩大范围，不添加其他工具。"
            + SlsQueryContext.SQL_RULES
            + "返回{\"arguments\":{完整sls_query_logs参数},\"purpose\":\"修正原因\"}。",
        encode(obj("query", task.get("query"), "failedArguments", failed,
            "error", error, "slsQueryContext", context.scope(), "inputSchema", remote.get("inputSchema"))));
    if (!(response.get("arguments") instanceof Map)) throw new IllegalStateException("模型未返回修正后的查询参数");
    var args = context.arguments("sls_query_logs", map(response.get("arguments")), map(remote.get("inputSchema")));
    if (s(args, "query").isBlank() || s(args, "query").equals(s(failed, "query")))
      throw new IllegalStateException("模型未修正失败的SLS查询");
    String originalFilter = s(failed, "query").split("\\|", 2)[0].trim();
    if (s(failed, "query").contains("|")
        && !s(args, "query").split("\\|", 2)[0].trim().equals(originalFilter))
      throw new IllegalStateException("查询修正改变了原始检索条件，未执行");
    return obj("tool", "sls_query_logs", "arguments", args, "purpose", "修正失败的SLS查询：" + s(response, "purpose"));
  }

  private void run(Map<String, Object> job, Protocol.Emitter emit) {
    String owner = s(job, "ownerUserId"), task = s(job, "resourceId");
    var t = store.get("diagnostic", owner, task);
    if (store.list("report", owner, task).stream()
        .anyMatch(r -> !Boolean.TRUE.equals(map(r.get("payload")).get("incomplete")))) {
      emit.emit(Protocol.event("aiops", "complete", obj("result", obj("taskId", task))));
      return;
    }
    if (!s(job, "retryOfJobId").isBlank() && n(job.get("attempt")) == 1) {
      try {
        store.delete("diagnostic_state", owner, "state_" + task);
      } catch (ApiException ignored) {
      }
    }
    if (store.list("step", owner, task).stream()
        .noneMatch(step -> s(step, "phase").equals("knowledge"))) {
      var refs = knowledge.retrieve(owner, s(t, "query"));
      String stepId = id("step"), auditId = id("tool");
      store.put(
          "step",
          owner,
          task,
          obj(
              "id",
              stepId,
              "taskId",
              task,
              "sequence",
              1,
              "phase",
              "knowledge",
              "status",
              "succeeded",
              "payload",
              obj("tool", "knowledge_retrieval", "results", refs.size()),
              "createdAt",
              now()));
      store.put(
          "audit",
          owner,
          task,
          obj(
              "id",
              auditId,
              "diagnosticTaskId",
              task,
              "sessionId",
              null,
              "toolName",
              "knowledge_retrieval",
              "status",
              "completed",
              "arguments",
              obj("query", t.get("query")),
              "resultSummary",
              "SOP检索返回 " + refs.size() + " 条真实来源",
              "startedAt",
              now(),
              "completedAt",
              now(),
              "durationMs",
              0,
              "createdAt",
              now()));
      for (var ref : refs) {
        var evidence =
            obj(
                "id",
                id("evidence"),
                "taskId",
                task,
                "stepId",
                stepId,
                "toolCallId",
                auditId,
                "kind",
                "knowledge_reference",
                "source",
                ref.get("source"),
                "summary",
                ref.get("excerpt"),
                "payload",
                ref,
                "createdAt",
                now());
        store.put("evidence", owner, task, evidence);
        emit.emit(Protocol.event("aiops", "reference.source", obj("reference", ref)));
      }
    }
    var context = new SlsQueryContext(model.config, t);
    var tools = mcp.tools(owner);
    if (tools.isEmpty())
      throw new IllegalStateException("请至少启用一个真实日志或指标查询 MCP 工具（如 SLS sls_query_logs）");
    List<String> selectedSkills = effectiveSkillIds(owner, t);
    String skills = skillsContext(owner, selectedSkills);
    Map<String, Object> state;
    try {
      state = map(store.get("diagnostic_state", owner, "state_" + task).get("state"));
    } catch (ApiException e) {
      state =
          obj(
              "plan",
              plan(
                  s(t, "query") + "\n告警上下文：" + encode(t.get("inputPayload")),
                  tools,
                  store.list("evidence", owner, task), context, store.list("audit", owner, task), skills),
              "next",
              0,
              "replans",
              0,
              "executed",
              0);
      checkpoint(owner, task, "plan", state);
    }
    emit.emit(
        Protocol.event(
            "aiops",
            "task.status",
            obj(
                "task",
                obj("id", task, "status", "running", "progress", 5, "message", "按检查点执行真实工具查询"))));
    List<Map<String, Object>> plan = new ArrayList<>(maps(state.get("plan")));
    int next = n(state.get("next")),
        executed = n(state.get("executed")),
        replans = n(state.get("replans"));
    int queryRepairs = n(state.get("queryRepairs"));
    while (executed < 8) {
      jobs.check(owner, s(job, "id"));
      if (next >= plan.size()) {
        if (replans >= 2) break;
        var evidence = store.list("evidence", owner, task);
        plan = plan(s(t, "query") + "\n告警上下文：" + encode(t.get("inputPayload")), tools, evidence, context, store.list("audit", owner, task), skills);
        next = 0;
        replans++;
        state.putAll(obj("plan", plan, "next", next, "replans", replans));
        checkpoint(owner, task, "replan", state);
        if (plan.isEmpty()) break;
      }
      var instruction = plan.get(next);
      String tool = s(instruction, "tool");
      var remote = tools.get(tool);
      if (remote == null) throw new IllegalStateException("MCP目录已变化");
      var step =
          obj(
              "id",
              id("step"),
              "taskId",
              task,
              "sequence",
              store.list("step", owner, task).size() + 1,
              "phase",
              "execute",
              "status",
              "running",
              "payload",
              obj("tool", tool, "purpose", instruction.get("purpose")),
              "createdAt",
              now());
      store.put("step", owner, task, step);
      String aid = id("tool");
      var audit =
          obj(
              "id",
              aid,
              "sessionId",
              null,
              "diagnosticTaskId",
              task,
              "toolName",
              tool,
              "status",
              "started",
              "arguments",
              AuditedTool.redact(instruction.get("arguments")),
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
      store.put("audit", owner, task, audit);
      long start = System.nanoTime();
      String output;
      try {
        var arguments = context.arguments(tool, map(instruction.get("arguments")),
            map(remote.get("inputSchema")));
        audit.put("arguments", AuditedTool.redact(arguments));
        store.put("audit", owner, task, audit);
        emit.emit(
            Protocol.event(
                "aiops",
                "tool.call",
                obj(
                    "toolCall",
                    obj(
                        "id",
                        aid,
                        "name",
                        tool,
                        "status",
                        "started",
                        "input",
                        audit.get("arguments")))));
        output = mcp.call(remote, arguments);
        jobs.check(owner, s(job, "id"));
        var ev =
            obj(
                "id",
                id("evidence"),
                "taskId",
                task,
                "stepId",
                step.get("id"),
                "toolCallId",
                aid,
                "kind",
                evidenceKind(tool),
                "source",
                tool,
                "summary",
                io.agentjava.knowledge.DocumentSplitter.excerpt(output, 500),
                "payload",
                obj("result", decode(output)),
                "createdAt",
                now());
        store.put("evidence", owner, task, ev);
        audit.putAll(
            obj(
                "status",
                "completed",
                "resultSummary",
                io.agentjava.knowledge.DocumentSplitter.excerpt(output, 2000)));
        step.put("status", "succeeded");
        emit.emit(
            Protocol.event(
                "aiops",
                "reference.source",
                obj(
                    "reference",
                    obj(
                        "id",
                        ev.get("id"),
                        "title",
                        tool,
                        "sourceType",
                        "log",
                        "excerpt",
                        ev.get("summary")))));
      } catch (java.util.concurrent.CancellationException e) {
        throw e;
      } catch (Exception e) {
        audit.putAll(obj("status", "failed", "errorMessage", safeFailure(e)));
        step.put("status", "failed");
      }
      audit.putAll(
          obj("completedAt", now(), "durationMs", (System.nanoTime() - start) / 1_000_000));
      store.put("audit", owner, task, audit);
      store.put("step", owner, task, step);
      emit.emit(
          Protocol.event(
              "aiops",
              "tool.call",
              obj(
                  "toolCall",
                  obj(
                      "id",
                      aid,
                      "name",
                      tool,
                      "status",
                      audit.get("status"),
                      "output",
                      audit.get("status").equals("failed") ? audit.get("errorMessage") : audit.get("resultSummary")))));
      next++;
      executed++;
      state.putAll(obj("next", next, "executed", executed));
      checkpoint(owner, task, "execute", state);
      if (tool.equals("sls_query_logs") && s(audit, "status").equals("failed")
          && s(audit, "errorMessage").matches("(?is).*(QueryError|SyntaxError|InvalidParameter|ParameterInvalid).*" )
          && queryRepairs < 1 && executed < 8) {
        queryRepairs++;
        state.put("queryRepairs", queryRepairs);
        try {
          jobs.check(owner, s(job, "id"));
          var corrected = repairQuery(t, remote, map(audit.get("arguments")), context, s(audit, "errorMessage"));
          plan.add(next, corrected);
          state.put("plan", plan);
          checkpoint(owner, task, "replan", state);
        } catch (java.util.concurrent.CancellationException error) {
          throw error;
        } catch (Exception error) {
          store.put("step", owner, task, obj("id", id("step"), "taskId", task,
              "sequence", store.list("step", owner, task).size() + 1, "phase", "replanner",
              "status", "failed", "payload", obj("error", safeFailure(error)), "createdAt", now()));
          checkpoint(owner, task, "query_repair_failed", state);
        }
      }
      emit.emit(
          Protocol.event(
              "aiops",
              "task.status",
              obj(
                  "task",
                  obj(
                      "id",
                      task,
                      "status",
                      "running",
                      "progress",
                      10 + executed * 9,
                      "message",
                      "已执行 " + executed + " 个工具步骤"))));
    }
    var evidence = store.list("evidence", owner, task);
    if (evidence.stream().noneMatch(DiagnosisService::hasLogRecords)) {
      var failures = store.list("audit", owner, task).stream()
          .filter(a -> s(a, "status").equals("failed")).toList();
      String reason = failures.isEmpty() ? "SLS查询已完成，但指定范围内没有匹配日志，根因未确认"
          : "未取得真实SLS日志证据；最近一次查询失败：" + s(failures.get(failures.size() - 1), "errorMessage");
      throw new JobRuntime.ExecutionFailure(reason, false);
    }
    Map<String, Object> reportData;
    try {
      reportData =
          jsonAnswer(
              "你是运维诊断报告作者。只依据给出的真实证据形成诊断，区分确认事实、推断与未知。证据不够时明确根因未确认。只提出修复建议，不执行修改。返回JSON"
                  + " {\"title\":\"...\",\"content\":\"完整中文Markdown报告，严格以# 告警分析报告开始，包含## 📋"
                  + " 活跃告警清单、## 🔍 告警根因分析1（详情、症状、日志证据ID、根因结论）、## 🛠️ 处理方案执行1（已执行只读查询、建议、预期效果）、## 📊"
                  + " 结论（整体评估、关键发现、后续建议、风险评估）\",\"rootCause\":\"...\",\"remediation\":\"...\",\"summary\":\"...\",\"keywords\":[\"...\"]}"
                  + (skills.isBlank()
                      ? "。"
                      : "\n\n用户选择的Skill指引（分析思路参考；事实与结论仍只依据真实证据）："
                          + skills),
              encode(
                  obj(
                      "query",
                      t.get("query"),
                      "input",
                      t.get("inputPayload"),
                      "toolOutcomes", store.list("audit", owner, task),
                      "evidence",
                      evidence)));
      if (s(reportData, "content").isBlank()) throw new IllegalStateException("报告正文为空");
    } catch (Exception error) {
      StringBuilder content = new StringBuilder("# 告警分析报告\n\n## 📋 活跃告警清单\n\n");
      content
          .append(s(t, "query"))
          .append("\n\n## 🔍 告警根因分析1\n\n报告模型暂不可用。以下仅列出已取得的真实证据，根因尚未确认。\n\n");
      for (var item : evidence)
        content
            .append("- 证据 `")
            .append(item.get("id"))
            .append("`（")
            .append(item.get("source"))
            .append("）：")
            .append(item.get("summary"))
            .append("\n");
      content.append(
          "\n"
              + "## 🛠️ 处理方案执行1\n\n"
              + "已执行步骤为证据链中的只读查询。建议人工核对证据后再决定修复措施；未执行任何写操作。\n\n"
              + "## 📊 结论\n\n"
              + "根因未确认。风险：缺少模型综合分析，不能据此直接执行修复。验证：检查日志证据和告警上下文，恢复模型后重新诊断。\n");
      reportData =
          obj(
              "title",
              "诊断证据报告（模型不可用）",
              "content",
              content.toString(),
              "rootCause",
              "未确认",
              "remediation",
              "人工核对证据后决定修复",
              "summary",
              "已保存真实查询证据，模型综合分析不可用",
              "keywords",
              List.of(),
              "fallback",
              true);
    }
    var failedCalls = store.list("audit", owner, task).stream()
        .filter(a -> s(a, "status").equals("failed")).toList();
    if (!failedCalls.isEmpty()) {
      var limitations = new StringBuilder("\n\n## 查询失败与分析限制\n\n");
      for (var call : failedCalls)
        limitations.append("- `").append(s(call, "toolName")).append("`（执行记录 `")
            .append(s(call, "id")).append("`）：").append(s(call, "errorMessage")).append("\n");
      limitations.append("\n上述失败调用不作为成功证据；报告仅依据已完成查询取得的证据，未取得的数据仍需核实。\n");
      reportData.put("content", s(reportData, "content") + limitations);
    }
    reportData.put("failedToolCallIds", failedCalls.stream().map(a -> s(a, "id")).toList());
    if (!selectedSkills.isEmpty()) reportData.put("skillIds", selectedSkills);
    List<String> eids = evidence.stream().map(e -> s(e, "id")).toList();
    var report =
        obj(
            "id",
            id("report"),
            "title",
            reportData.get("title"),
            "content",
            reportData.get("content"),
            "payload",
            reportData,
            "evidenceIds",
            eids,
            "createdAt",
            now());
    store.put("report", owner, task, report);
    for (String eid : eids)
      store.put(
          "report_evidence",
          owner,
          task,
          obj(
              "id",
              id("link"),
              "taskId",
              task,
              "reportId",
              report.get("id"),
              "evidenceId",
              eid,
              "createdAt",
              now()));
    checkpoint(owner, task, "report", obj("reportId", report.get("id"), "evidenceIds", eids));
    t.put("resultPayload", obj("reportId", report.get("id"), "evidenceCount", eids.size()));
    store.put("diagnostic", owner, "", t);
    emit.emit(
        Protocol.event(
            "aiops",
            "report",
            obj(
                "report",
                obj(
                    "id",
                    report.get("id"),
                    "title",
                    report.get("title"),
                    "content",
                    report.get("content"),
                    "format",
                    "markdown"))));
    emit.emit(
        Protocol.event(
            "aiops", "complete", obj("result", obj("taskId", task, "reportId", report.get("id")))));
  }

  private static boolean hasLogRecords(Map<String, Object> evidence) {
    if (!s(evidence, "kind").equals("log")) return false;
    for (var block : maps(map(map(evidence.get("payload")).get("result")).get("content"))) {
      try {
        if (hasRecords(MAPPER.readValue(s(block, "text"), Object.class))) return true;
      } catch (Exception ignored) {
      }
    }
    return false;
  }

  private static boolean hasRecords(Object value) {
    if (value instanceof List<?> rows) return !rows.isEmpty();
    if (value instanceof Map<?, ?> map)
      return map.entrySet().stream().anyMatch(e ->
          Set.of("logs", "data", "rows", "records", "result", "items").contains(str(e.getKey()))
              && hasRecords(e.getValue()));
    return false;
  }

  public synchronized Map<String, Object> persistCase(String owner, String task) {
    var t = store.get("diagnostic", owner, task);
    if (!s(t, "status").equals("succeeded")) throw new ApiException("BUSINESS_CONFLICT");
    var existing = store.list("case", owner, task);
    if (!existing.isEmpty()) return existing.get(0);
    var reports = store.list("report", owner, task);
    if (reports.isEmpty()) throw new ApiException("BUSINESS_CONFLICT");
    var report = reports.get(reports.size() - 1);
    var payload = map(report.get("payload"));
    String kb = "kb_" + owner;
    Map<String, Object> document;
    var docs = store.list("document", owner, kb);
    var prior =
        docs.stream()
            .filter(
                d ->
                    s(d, "status").equals("ready")
                        && s(map(d.get("metadata")), "knowledgeType").equals("diagnostic-case")
                        && s(map(d.get("metadata")), "diagnosticTaskId").equals(task))
            .findFirst();
    if (prior.isPresent()) document = prior.get();
    else
      document =
          map(
              knowledge
                  .upload(
                      owner,
                      kb,
                      "case-" + task + ".md",
                      "text/markdown",
                      ("# " + s(report, "title") + "\n\n" + s(report, "content"))
                          .getBytes(StandardCharsets.UTF_8),
                      obj("strategy", "markdown-heading"),
                      false,
                      obj(
                          "knowledgeType",
                          "diagnostic-case",
                          "diagnosticTaskId",
                          task,
                          "reportId",
                          report.get("id"),
                          "evidenceIds",
                          report.get("evidenceIds")))
                  .get("document"));
    var indexes = store.list("index", owner, s(document, "id"));
    var index =
        indexes.isEmpty()
            ? knowledge.createIndex(owner, kb, s(document, "id"), null)
            : indexes.get(0);
    var alert = map(map(t.get("inputPayload")).get("alert"));
    var c =
        obj(
            "id",
            "case_" + task,
            "taskId",
            task,
            "reportId",
            report.get("id"),
            "documentId",
            document.get("id"),
            "indexTaskId",
            index.get("id"),
            "alertName",
            alert.getOrDefault("alertName", ""),
            "service",
            alert.getOrDefault("service", ""),
            "keywords",
            payload.getOrDefault("keywords", List.of()),
            "rootCause",
            payload.getOrDefault("rootCause", "未确认"),
            "remediation",
            payload.getOrDefault("remediation", ""),
            "summary",
            payload.getOrDefault("summary", report.get("title")),
            "evidenceIds",
            report.get("evidenceIds"),
            "createdAt",
            now());
    return store.put("case", owner, task, c);
  }

  public Map<String, Object> saveLegacy(String owner, String task) {
    var diagnosis = store.get("diagnostic", owner, task);
    var reports = store.list("report", owner, task);
    if (!s(diagnosis, "status").equals("succeeded") || reports.isEmpty())
      throw new ApiException("BUSINESS_CONFLICT");
    var report = reports.get(reports.size() - 1);
    String kb = "kb_" + owner;
    var document =
        map(
            knowledge
                .upload(
                    owner,
                    kb,
                    "diagnosis-" + task + ".md",
                    "text/markdown",
                    ("# 手动保存诊断报告\n\n" + s(report, "content")).getBytes(StandardCharsets.UTF_8),
                    obj("strategy", "markdown-heading"),
                    false,
                    obj(
                        "knowledgeType",
                        "diagnostic-report",
                        "diagnosticTaskId",
                        task,
                        "reportId",
                        report.get("id"),
                        "evidenceIds",
                        report.get("evidenceIds")))
                .get("document"));
    var index = knowledge.createIndex(owner, kb, s(document, "id"), null);
    return obj("document", document, "task", index, "scheduled", true);
  }

  public List<Map<String, Object>> cases(String owner) {
    return store.list("case", owner, null);
  }
}
