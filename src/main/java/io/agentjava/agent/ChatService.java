package io.agentjava.agent;

import static io.agentjava.store.Json.*;

import io.agentjava.api.*;
import io.agentjava.knowledge.KnowledgeService;
import io.agentjava.mcp.McpService;
import io.agentjava.model.QwenProvider;
import io.agentjava.store.Store;
import io.agentscope.core.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.Toolkit;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
  private final Store store;
  private final QwenProvider model;
  private final KnowledgeService knowledge;
  private final McpService mcp;
  private final AssetService assets;
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
  private static final ExecutorService TITLE_POOL =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "chat-title");
            t.setDaemon(true);
            return t;
          });

  public ChatService(
      Store store,
      QwenProvider model,
      KnowledgeService knowledge,
      McpService mcp,
      AssetService assets) {
    this.store = store;
    this.model = model;
    this.knowledge = knowledge;
    this.mcp = mcp;
    this.assets = assets;
  }

  public List<Map<String, Object>> list(String owner) {
    return store.list("chat", owner, "").stream().map(this::view).toList();
  }

  public Map<String, Object> create(String owner, Map<String, Object> req) {
    var session =
        obj(
            "id",
            id("chat"),
            "title",
            s(req, "title").isBlank() ? "新对话" : s(req, "title"),
            "createdAt",
            now(),
            "updatedAt",
            now(),
            "mode",
            "every_30_turns",
            "summary",
            "",
            "compacted",
            0,
            "turnsAtLastCompaction",
            0,
            "contextTokens",
            0,
            "lastCompactedAt",
            null);
    return view(store.put("chat", owner, "", session));
  }

  public Map<String, Object> raw(String owner, String id) {
    return store.get("chat", owner, id);
  }

  private Map<String, Object> view(Map<String, Object> s) {
    var out = new LinkedHashMap<>(s);
    int window = Math.max(1, model.config.window());
    out.put(
        "memory",
        obj(
            "mode",
            s.get("mode"),
            "contextTokens",
            s.get("contextTokens"),
            "contextWindowTokens",
            window,
            "contextUsagePercent",
            100.0 * n(s.get("contextTokens")) / window,
            "compactedMessageCount",
            s.get("compacted"),
            "lastCompactedAt",
            s.get("lastCompactedAt"),
            "canCompact",
            n(s.get("contextTokens")) > 0));
    for (String key :
        List.of(
            "mode",
            "summary",
            "compacted",
            "contextTokens",
            "lastCompactedAt",
            "turnsAtLastCompaction")) out.remove(key);
    return out;
  }

  public List<Map<String, Object>> messages(String owner, String id) {
    raw(owner, id);
    return store.list("message", owner, id).stream()
        .sorted(Comparator.comparingInt(m -> n(m.get("sequence"))))
        .toList();
  }

  public Map<String, Object> detail(String owner, String id) {
    return obj("session", view(raw(owner, id)), "messages", messages(owner, id));
  }

  public static int tokens(String text) {
    int count = 0;
    for (int cp : text.codePoints().toArray()) count += cp > 127 ? 1 : 0;
    return count + Math.max(1, (text.length() - count + 3) / 4);
  }

  public synchronized Map<String, Object> append(
      String owner, String id, String role, String content, Map<String, Object> meta) {
    var session = raw(owner, id);
    if (!Set.of("user", "assistant").contains(role) || content.isBlank())
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var previous = messages(owner, id);
    int sequence =
        previous.isEmpty() ? 1 : n(previous.get(previous.size() - 1).get("sequence")) + 1;
    var msg =
        obj(
            "id",
            id("msg"),
            "sessionId",
            id,
            "role",
            role,
            "content",
            content,
            "metadata",
            meta,
            "sequence",
            sequence,
            "createdAt",
            now());
    store.put("message", owner, id, msg);
    session.put("contextTokens", n(session.get("contextTokens")) + tokens(content));
    session.put("updatedAt", now());
    if (s(session, "title").equals("新对话") && role.equals("user")) {
      String fallback = io.agentjava.knowledge.DocumentSplitter.excerpt(content, 80);
      session.put("title", fallback);
      generateTitle(owner, id, content, fallback);
    }
    store.put("chat", owner, "", session);
    return obj("session", view(session), "message", msg);
  }

  private void generateTitle(String owner, String id, String question, String fallback) {
    TITLE_POOL.submit(
        () -> {
          try {
            String title =
                model
                    .answer(
                        "你负责生成会话标题。根据用户消息提炼不超过20个字的标题，直接输出标题文本，不要引号、句号或解释。",
                        io.agentjava.knowledge.DocumentSplitter.excerpt(question, 500))
                    .trim()
                    .replaceAll("\\s+", " ");
            if (!title.isBlank())
              applyTitle(
                  owner, id, fallback, io.agentjava.knowledge.DocumentSplitter.excerpt(title, 80));
          } catch (Exception e) {
            // 标题生成失败静默保留截断兜底，不打扰用户
          }
        });
  }

  private synchronized void applyTitle(String owner, String id, String expected, String title) {
    var session = raw(owner, id);
    if (!s(session, "title").equals(expected)) return;
    session.put("title", title);
    store.put("chat", owner, "", session);
  }

  public Map<String, Object> memory(String owner, String id, String mode) {
    if (!Set.of("manual", "every_30_turns", "context_70_percent").contains(mode))
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var session = raw(owner, id);
    session.put("mode", mode);
    store.put("chat", owner, "", session);
    return view(session);
  }

  public Map<String, Object> compact(String owner, String id) {
    synchronized (locks.computeIfAbsent(owner + id, k -> new Object())) {
      var session = raw(owner, id);
      var messages = messages(owner, id);
      int count = Math.max(0, messages.size() - 6);
      int prior = Math.min(n(session.get("compacted")), count);
      if (count <= prior) return view(session);
      String summary =
          model.answer(
              "你负责压缩历史。保留用户事实、约束、未完成事项、工具结论与引用，不添加新事实。",
              s(session, "summary") + "\n" + encode(messages.subList(prior, count)));
      session.putAll(
          obj(
              "summary",
              summary,
              "compacted",
              count,
              "turnsAtLastCompaction",
              messages.stream().filter(m -> s(m, "role").equals("assistant")).count(),
              "contextTokens",
              tokens(summary)
                  + messages.subList(count, messages.size()).stream()
                      .mapToInt(m -> tokens(s(m, "content")))
                      .sum(),
              "lastCompactedAt",
              now(),
              "updatedAt",
              now()));
      store.put("chat", owner, "", session);
      return view(session);
    }
  }

  public synchronized Map<String, Object> clear(String owner, String id, boolean delete) {
    var session = raw(owner, id);
    int count = messages(owner, id).size();
    for (var msg : messages(owner, id))
      store.db.update(
          "DELETE FROM resources WHERE kind='feedback' AND owner=? AND parent=?",
          owner,
          s(msg, "id"));
    store.children(owner, id);
    if (delete) store.delete("chat", owner, id);
    else {
      session.putAll(
          obj(
              "summary",
              "",
              "compacted",
              0,
              "turnsAtLastCompaction",
              0,
              "contextTokens",
              0,
              "lastCompactedAt",
              null,
              "updatedAt",
              now()));
      store.put("chat", owner, "", session);
    }
    return delete
        ? obj("deleted", true, "sessionId", id)
        : obj("cleared", true, "sessionId", id, "deletedMessages", count);
  }

  public void stream(
      String owner, String id, String content, Map<String, Object> meta, Protocol.Emitter emit) {
    synchronized (locks.computeIfAbsent(owner + id, k -> new Object())) {
      var before = raw(owner, id);
      int context = n(before.get("contextTokens")) + tokens(content);
      long pendingTurns =
          messages(owner, id).stream().filter(m -> s(m, "role").equals("assistant")).count()
              - n(before.get("turnsAtLastCompaction"));
      if ((s(before, "mode").equals("every_30_turns") && pendingTurns >= 30)
          || (s(before, "mode").equals("context_70_percent")
              && context * 100.0 / Math.max(1, model.config.window()) >= 70)) compact(owner, id);

      var session = raw(owner, id);
      if ((n(session.get("contextTokens")) + tokens(content))
              * 100.0
              / Math.max(1, model.config.window())
          >= 95) throw new ApiException("CHAT_CONTEXT_LIMIT_REACHED");
      append(owner, id, "user", content, meta);
      session = raw(owner, id);
      List<Map<String, Object>> citations = new CopyOnWriteArrayList<>();
      List<String> reasoning = new CopyOnWriteArrayList<>();
      List<String> auditIds = new CopyOnWriteArrayList<>();
      Protocol.Emitter toolEmit =
          e -> {
            if (s(e, "type").equals("tool.call")
                && s(map(e.get("toolCall")), "status").equals("started"))
              auditIds.add(s(map(e.get("toolCall")), "id"));
            emit.emit(e);
          };
      Toolkit tools = new Toolkit();
      tools.registerAgentTool(
          new AuditedTool(
              store,
              owner,
              id,
              "chat",
              "get_current_time",
              "返回真实当前时间",
              AuditedTool.schema("timezone", "IANA时区，默认Asia/Shanghai"),
              args ->
                  ZonedDateTime.now(
                          ZoneId.of(
                              s(args, "timezone").isBlank()
                                  ? "Asia/Shanghai"
                                  : s(args, "timezone")))
                      .toString(),
              toolEmit));
      tools.registerAgentTool(
          new AuditedTool(
              store,
              owner,
              id,
              "chat",
              "knowledge_retrieval",
              "检索当前用户知识库，返回有来源、排名与分数的证据",
              obj(
                  "type",
                  "object",
                  "properties",
                  obj(
                      "query", obj("type", "string", "description", "检索问题"),
                      "topK", obj("type", "integer", "minimum", 1, "maximum", 5),
                      "filters",
                          obj(
                              "type",
                              "object",
                              "properties",
                              obj(
                                  "knowledgeBaseIds",
                                      obj("type", "array", "items", obj("type", "string")),
                                  "documentIds",
                                      obj("type", "array", "items", obj("type", "string")),
                                  "metadata", obj("type", "object")))),
                  "required",
                  List.of("query")),
              args -> {
                var refs = knowledge.retrieve(owner, args);
                for (var ref : refs) {
                  citations.add(ref);
                  emit.emit(Protocol.event("chat", "reference.source", obj("reference", ref)));
                }
                return encode(
                    obj(
                        "query",
                        args.get("query"),
                        "topK",
                        args.getOrDefault("topK", 5),
                        "results",
                        refs,
                        "citations",
                        refs));
              },
              toolEmit));
      tools.registerAgentTool(
          new AuditedTool(
              store,
              owner,
              id,
              "chat",
              "load_skill",
              "按需加载已选择Skill正文",
              AuditedTool.schema("skill_id", "技能id或name"),
              args -> assets.load(owner, s(args, "skill_id")),
              toolEmit));
      for (var remote : mcp.tools(owner).values())
        tools.registerAgentTool(
            new AuditedTool(
                store,
                owner,
                id,
                "chat",
                s(remote, "name"),
                s(remote, "description"),
                map(remote.get("inputSchema")),
                args -> mcp.call(remote, args),
                toolEmit));
      List<Msg> input = new ArrayList<>();
      String system = assets.system(owner) + "\n需要知识或日志时自主调用工具；知识依据以[来源标题]引用。失败应明确说明，不编造来源。";
      if (!s(session, "summary").isBlank()) system += "\n历史摘要：" + s(session, "summary");
      var history = messages(owner, id);
      for (int i = Math.min(n(session.get("compacted")), history.size()); i < history.size(); i++) {
        var m = history.get(i);
        input.add(
            s(m, "role").equals("user")
                ? new UserMessage(s(m, "content"))
                : new AssistantMessage(s(m, "content")));
      }
      AtomicReference<Msg> finalMessage = new AtomicReference<>();
      AtomicInteger seq = new AtomicInteger();
      try (var agent =
          ReActAgent.builder()
              .name("workspace-assistant")
              .sysPrompt(system)
              .model(model.model())
              .toolkit(tools)
              .maxIters(12)
              .maxRetries(model.config.number("llm", "maxRetries", 2))
              .build()) {
        agent
            .streamEvents(input, RuntimeContext.builder().userId(owner).sessionId(id).build())
            .doOnNext(
                e -> {
                  if (e instanceof ThinkingBlockDeltaEvent thinking) {
                    reasoning.add(thinking.getDelta());
                    emit.emit(
                        Protocol.event(
                            "chat",
                            "reasoning.delta",
                            obj("delta", thinking.getDelta(), "sequence", seq.incrementAndGet())));
                  }
                  if (e instanceof AgentResultEvent result) finalMessage.set(result.getResult());
                  if (e instanceof ExceedMaxItersEvent)
                    throw new IllegalStateException("Agent达到最大迭代次数");
                })
            .blockLast(Duration.ofSeconds(model.config.number("llm", "timeoutSeconds", 120) * 3L));
      }
      Msg result = finalMessage.get();
      if (result == null || result.getTextContent().isBlank())
        throw new IllegalStateException("Agent未产生最终回答");
      String answer = result.getTextContent();
      int sequence = 0;
      for (int cp : answer.codePoints().toArray())
        emit.emit(
            Protocol.event(
                "chat",
                "content.delta",
                obj("delta", new String(Character.toChars(cp)), "sequence", ++sequence)));
      var saved =
          append(
              owner,
              id,
              "assistant",
              answer,
              obj("citations", citations, "reasoning", reasoning, "toolCallIds", auditIds));
      session = raw(owner, id);
      emit.emit(Protocol.event("chat", "complete", obj("result", saved)));
    }
  }
}
