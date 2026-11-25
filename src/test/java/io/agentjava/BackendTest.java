package io.agentjava;

import static io.agentjava.store.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.agentjava.agent.*;
import io.agentjava.aiops.*;
import io.agentjava.api.*;
import io.agentjava.auth.*;
import io.agentjava.config.*;
import io.agentjava.feedback.*;
import io.agentjava.jobs.*;
import io.agentjava.knowledge.*;
import io.agentjava.mcp.*;
import io.agentjava.model.*;
import io.agentjava.store.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BackendTest {
  @TempDir Path root;
  ProjectConfig config;
  Store store;
  AuthService auth;
  JobRuntime jobs;
  QwenProvider model;
  VectorStore vector;
  McpService mcp;
  KnowledgeService knowledge;
  ChatService chat;
  AssetService assets;
  DiagnosisService diag;
  AlertService alerts;
  InspectionService inspection;
  FeedbackService feedback;
  MockMvc mvc;
  String alice, bob, token, btoken;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("config"));
    Files.writeString(
        root.resolve("config/project.json"),
        encode(
            obj(
                "backend",
                obj("memoryDatabaseUrl", "jdbc:sqlite:./test.db"),
                "llm",
                obj(
                    "chatModel",
                    "test",
                    "modelCapabilities",
                    obj("test", obj("contextWindowTokens", 10000))))));
    Files.writeString(root.resolve("config/user.project.json"), "{}");
    config = new ProjectConfig(root);
    store = new Store(config);
    auth = new AuthService(store);
    var a =
        auth.register(
            obj("email", "alice@example.test", "displayName", "Alice", "password", "TestPass123!"));
    alice = s(map(a.get("user")), "id");
    token = s(a, "accessToken");
    var b =
        auth.register(
            obj("email", "bob@example.test", "displayName", "Bob", "password", "TestPass123!"));
    bob = s(map(b.get("user")), "id");
    btoken = s(b, "accessToken");
    jobs = new JobRuntime(store);
    model =
        mock(
            QwenProvider.class,
            withSettings().useConstructor(config).defaultAnswer(CALLS_REAL_METHODS));
    doReturn(mock(io.agentscope.extensions.model.openai.OpenAIChatModel.class)).when(model).model();
    doReturn("持久摘要：Alice使用Java").when(model).answer(anyString(), anyString());
    vector = mock(VectorStore.class);
    mcp = mock(McpService.class);
    when(mcp.tools(anyString())).thenReturn(Map.of());
    knowledge = new KnowledgeService(store, vector, model, jobs, config);
    assets = new AssetService(store);
    chat = new ChatService(store, model, knowledge, mcp, assets);
    diag = new DiagnosisService(store, jobs, model, mcp, knowledge);
    alerts =
        mock(
            AlertService.class,
            withSettings()
                .useConstructor(config, new SunfireAlertStore(store))
                .defaultAnswer(CALLS_REAL_METHODS));
    doReturn(List.of()).when(alerts).active();
    inspection = new InspectionService(store, diag, mcp, alerts, model, config);
    feedback = new FeedbackService(store);
    var filter = new RequestFilter(auth);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ApiController(
                    store,
                    auth,
                    config,
                    model,
                    jobs,
                    knowledge,
                    chat,
                    assets,
                    mcp,
                    diag,
                    alerts,
                    inspection,
                    feedback,
                    filter))
            .setControllerAdvice(new ApiErrorAdvice())
            .addFilters(filter)
            .build();
  }

  @AfterEach
  void cleanup() {
    jobs.stop();
    inspection.stop();
  }

  @Test
  void authHashRevocationAndEnvelope() throws Exception {
    assertEquals(alice, s(auth.current(token), "id"));
    assertEquals("business", Protocol.error("CHAT_CONTEXT_LIMIT_REACHED").get("category"));
    assertThrows(
        ApiException.class,
        () -> auth.login(obj("email", "alice@example.test", "password", "bad")));
    String saved =
        store.db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, alice);
    assertFalse(saved.contains("TestPass123"));
    assertTrue(saved.contains(":"));
    assertFalse(
        store
            .db
            .queryForObject(
                "SELECT token_hash FROM auth_sessions WHERE user_id=?", String.class, alice)
            .contains(token));
    mvc.perform(get("/chat/sessions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.httpStatus").value(401))
        .andExpect(header().exists("X-Request-ID"));
    auth.logout(token);
    assertThrows(ApiException.class, () -> auth.current(token));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chat",
        "message",
        "prompt",
        "skill",
        "document",
        "index",
        "mcp",
        "diagnostic",
        "step",
        "evidence",
        "report",
        "report_evidence",
        "checkpoint",
        "case",
        "feedback",
        "audit",
        "diagnostic_state"
      })
  void allResourcesOwnerIsolation(String kind) {
    var resource = obj("id", id(kind));
    store.put(kind, alice, "parent", resource);
    assertEquals(alice, s(store.get(kind, alice, s(resource, "id")), "ownerUserId"));
    assertThrows(ApiException.class, () -> store.get(kind, bob, s(resource, "id")));
    assertTrue(store.list(kind, bob, null).isEmpty());
    assertThrows(ApiException.class, () -> store.delete(kind, bob, s(resource, "id")));
    assertNotNull(store.get(kind, alice, s(resource, "id")));
  }

  @Test
  void sqlMigrationRestartAndPersistence() {
    store.put("evidence", alice, "task", obj("id", "ev-persistent", "summary", "真实证据"));
    Store again = new Store(config);
    assertEquals("真实证据", s(again.get("evidence", alice, "ev-persistent"), "summary"));
    assertEquals(
        7, again.db.queryForObject("SELECT count(*) FROM schema_migrations", Integer.class));
  }

  @Test
  void knowledgeListUsesUploadTimeDescendingRegardlessOfUpdates() throws Exception {
    String kb = "kb_" + alice;
    store.put("document", alice, kb, obj("id", "old-doc", "status", "ready",
        "uploadedAt", "2026-10-04T00:00:00Z", "updatedAt", "2026-10-06T00:00:00Z"));
    store.put("document", alice, kb, obj("id", "new-doc", "status", "ready",
        "uploadedAt", "2026-10-05T08:00:00.100+08:00", "updatedAt", "2026-10-05T00:00:00Z"));
    store.put("document", alice, kb, obj("id", "deleted-doc", "status", "deleted",
        "uploadedAt", "2026-10-07T00:00:00Z"));
    mvc.perform(get("/knowledge-bases/" + kb + "/documents").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(2))
        .andExpect(jsonPath("$.data.items[0].id").value("new-doc"))
        .andExpect(jsonPath("$.data.items[1].id").value("old-doc"));
  }

  @Test
  void diagnosisHistoryUsesUpdateTimeDescendingAndKeepsEvidenceChronological() throws Exception {
    jobs.stop();
    var older = diag.create(alice, obj("query", "较早创建的任务"));
    var newer = diag.create(alice, obj("query", "较晚创建的任务"));
    var lastCreated = diag.create(alice, obj("query", "最后创建的任务"));
    older.put("updatedAt", "2026-10-05T00:00:00Z");
    newer.put("updatedAt", "2026-10-05T08:00:00.100+08:00");
    lastCreated.put("updatedAt", "2026-10-04T00:00:00Z");
    store.put("diagnostic", alice, "", older);
    store.put("diagnostic", alice, "", newer);
    store.put("diagnostic", alice, "", lastCreated);
    store.db.update("UPDATE resources SET created_at=? WHERE kind='diagnostic' AND id=?",
        "2026-10-01T00:00:00Z", s(older, "id"));
    store.db.update("UPDATE resources SET created_at=? WHERE kind='diagnostic' AND id=?",
        "2026-10-02T00:00:00Z", s(newer, "id"));
    store.db.update("UPDATE resources SET created_at=? WHERE kind='diagnostic' AND id=?",
        "2026-10-03T00:00:00Z", s(lastCreated, "id"));
    mvc.perform(get("/aiops/diagnostics").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(s(newer, "id")))
        .andExpect(jsonPath("$.data.items[1].id").value(s(older, "id")))
        .andExpect(jsonPath("$.data.items[2].id").value(s(lastCreated, "id")));
    store.put("evidence", alice, s(older, "id"), obj("id", "ev-first"));
    store.put("evidence", alice, s(older, "id"), obj("id", "ev-second"));
    store.db.update("UPDATE resources SET created_at=? WHERE kind='evidence' AND id=?",
        "2026-10-05T00:00:00Z", "ev-first");
    store.db.update("UPDATE resources SET created_at=? WHERE kind='evidence' AND id=?",
        "2026-10-05T00:01:00Z", "ev-second");
    assertEquals(List.of("ev-first", "ev-second"),
        maps(diag.chain(alice, s(older, "id")).get("evidence")).stream().map(e -> s(e, "id")).toList());
  }

  @Test
  void jsonDeepMergeNoEnvironmentSource() {
    assertEquals(
        encode(obj("a", obj("b", 1, "c", 3))),
        encode(ProjectConfig.merge(obj("a", obj("b", 1, "c", 2)), obj("a", obj("c", 3)))));
  }

  @Test
  void documentPolicyAndChunking() {
    String kb = "kb_" + alice;
    var d =
        map(
            knowledge
                .upload(
                    alice,
                    kb,
                    "one.md",
                    "text/markdown",
                    "# 章节\n\nJava知识\n\n中文证据".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    obj("strategy", "paragraph"),
                    false,
                    obj())
                .get("document"));
    assertThrows(ApiException.class, () -> knowledge.get(bob, kb, s(d, "id")));
    assertThrows(
        ApiException.class,
        () ->
            knowledge.upload(
                alice,
                kb,
                "bad.txt",
                "text/plain",
                new byte[] {1},
                obj("strategy", "paragraph"),
                false,
                obj()));
    assertThrows(
        ApiException.class,
        () ->
            knowledge.upload(
                alice,
                kb,
                "one.md",
                "text/markdown",
                "new".getBytes(),
                obj("strategy", "paragraph"),
                false,
                obj()));
    assertThrows(
        ApiException.class,
        () -> DocumentSplitter.validate(obj("strategy", "paragraph", "maxCharacters", 100)));
    assertThrows(
        ApiException.class,
        () ->
            DocumentSplitter.validate(
                obj(
                    "strategy",
                    "fixed-character",
                    "maxCharacters",
                    100,
                    "overlapCharacters",
                    100)));
    var chunks =
        DocumentSplitter.split(
            "😀".repeat(210),
            obj("strategy", "fixed-character", "maxCharacters", 100, "overlapCharacters", 10));
    assertEquals(3, chunks.size());
    assertEquals(100, chunks.get(0).codePointCount(0, chunks.get(0).length()));
    assertEquals(1, n(knowledge.preview(alice, kb, s(d, "id")).get("totalChunks")));
  }

  @Test
  void vectorFilterEscapesAndRejectsWrongKnowledgeBase() {
    String f = VectorStore.scope("a\"b", "kb_a\"b");
    assertTrue(f.contains("tenantId"));
    assertTrue(f.contains("ownerUserId"));
    assertTrue(f.contains("a\\\"b"));
    assertThrows(IllegalArgumentException.class, () -> VectorStore.scope(alice, "kb_" + bob));
  }

  @Test
  void positiveBm25Idf() {
    var ranks =
        KnowledgeService.bm25(
            "Java",
            List.of(obj("id", "a", "content", "Java Java"), obj("id", "b", "content", "Java")));
    assertEquals(2, ranks.size());
    assertTrue(((Number) ranks.get(0).get("score")).doubleValue() > 0);
  }

  @Test
  void chatPersistMemoryDoesNotDeleteHistory() throws Exception {
    String id = s(chat.create(alice, obj()), "id");
    for (int i = 0; i < 10; i++)
      chat.append(alice, id, i % 2 == 0 ? "user" : "assistant", "Java 第" + i + "轮", obj());
    assertEquals(10, chat.messages(alice, id).size());
    var compact = chat.compact(alice, id);
    assertEquals(4, n(map(compact.get("memory")).get("compactedMessageCount")));
    assertEquals(10, chat.messages(alice, id).size());
    assertThrows(ApiException.class, () -> chat.detail(bob, id));
    mvc.perform(
            put("/chat/sessions/" + id + "/memory")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"mode\":\"manual\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.memory.mode").value("manual"));
    assertEquals(10, n(chat.clear(alice, id, false).get("deletedMessages")));
    assertEquals(0, chat.messages(alice, id).size());
  }

  @Test
  void skillProgressiveLoadingAndFeedbackScope() {
    var skill =
        assets.skill(
            alice,
            "SKILL.md",
            "---\nname: java-check\ndescription: 验收知识\n---\n\nSECRET_SKILL_BODY");
    var c = assets.configuration(alice);
    assets.select(
        alice,
        obj(
            "systemPromptId",
            map(c.get("selection")).get("systemPromptId"),
            "skillIds",
            List.of(skill.get("id"))));
    assertFalse(assets.system(alice).contains("SECRET_SKILL_BODY"));
    assertTrue(assets.load(alice, "java-check").contains("SECRET_SKILL_BODY"));
    assertThrows(ApiException.class, () -> assets.load(bob, s(skill, "id")));
    String sid = s(chat.create(alice, obj()), "id");
    var msg =
        map(
            chat.append(
                    alice, sid, "assistant", "回答", obj("citations", List.of(obj("id", "source-1"))))
                .get("message"));
    var f =
        feedback.upsert(
            alice,
            obj(
                "targetType",
                "citation",
                "targetId",
                msg.get("id"),
                "subjectId",
                "source-1",
                "rating",
                "positive"));
    var updated =
        feedback.upsert(
            alice,
            obj(
                "targetType",
                "citation",
                "targetId",
                msg.get("id"),
                "subjectId",
                "source-1",
                "rating",
                "negative"));
    assertEquals(f.get("id"), updated.get("id"));
    assertThrows(
        ApiException.class,
        () ->
            feedback.upsert(
                bob,
                obj(
                    "targetType",
                    "chat_message",
                    "targetId",
                    msg.get("id"),
                    "rating",
                    "positive")));
    assertThrows(
        ApiException.class,
        () ->
            feedback.upsert(
                alice,
                obj(
                    "targetType",
                    "citation",
                    "targetId",
                    msg.get("id"),
                    "subjectId",
                    "invented",
                    "rating",
                    "positive")));
  }

  @Test
  void durableJobsRetryCancelAndRecovery() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    jobs.handlers.put(
        "test",
        (j, e) -> {
          if (attempts.incrementAndGet() == 1) throw new IllegalStateException();
          e.emit(Protocol.event("aiops", "complete", obj()));
        });
    var j = jobs.enqueue(alice, "test", "resource", obj(), null);
    String id = s(j, "id");
    await(() -> s(jobs.get(alice, id), "status").equals("succeeded"));
    assertEquals(2, attempts.get());
    assertEquals(1, jobs.events(alice, id, 0).size());
    assertTrue(jobs.events(alice, id, 1).isEmpty());
    assertThrows(ApiException.class, () -> jobs.get(bob, id));
    var waiting = jobs.enqueue(alice, "unregistered", "wait", obj(), null);
    jobs.cancel(alice, s(waiting, "id"));
    assertEquals("cancelled", s(jobs.get(alice, s(waiting, "id")), "status"));
    assertEquals("queued", s(jobs.retry(alice, s(waiting, "id")), "status"));
    var abandoned = jobs.enqueue(alice, "test", "recovered", obj(), null);
    store.db.update(
        "UPDATE background_jobs SET status='running',lease_owner='dead',lease_expires_at=? WHERE"
            + " id=?",
        Instant.now().minusSeconds(1).toString(),
        abandoned.get("id"));
    await(() -> s(jobs.get(alice, s(abandoned, "id")), "status").equals("succeeded"));
  }

  @Test
  void apiCrudSseFailureAndCrossUser() throws Exception {
    var created =
        mvc.perform(
                post("/chat/sessions")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isCreated())
            .andReturn();
    String session = s(map(decode(created.getResponse().getContentAsString()).get("data")), "id");
    mvc.perform(get("/chat/sessions/" + session).header("Authorization", "Bearer " + btoken))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/chat/sessions/" + session + "/messages:stream")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"测试\"}"))
        .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event: error")));
    mvc.perform(get("/unknown").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void failedDiagnosisNeverCreatesCase() throws Exception {
    var d = diag.create(alice, obj("query", "诊断服务超时"));
    String task = s(d, "id");
    var j = jobs.resource(alice, task);
    store.db.update("UPDATE background_jobs SET max_attempts=1 WHERE id=?", j.get("id"));
    await(() -> s(diag.get(alice, task), "status").equals("failed"));
    assertTrue(diag.cases(alice).isEmpty());
    var reports = maps(diag.chain(alice, task).get("reports"));
    assertEquals(1, reports.size());
    assertTrue(s(reports.get(0), "content").contains("根因未确认"));
    assertTrue(Boolean.TRUE.equals(map(reports.get(0).get("payload")).get("incomplete")));
    assertThrows(ApiException.class, () -> diag.chain(bob, task));
  }

  @Test
  void embeddingsBatchingAndDimensionValidation() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          var request =
              decode(
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8));
          List<?> inputs = (List<?>) request.get("input");
          assertTrue(inputs.size() <= 10);
          assertTrue(inputs.stream().allMatch(x -> x instanceof String));
          assertEquals(1024, n(request.get("dimensions")));
          calls.incrementAndGet();
          var rows = new ArrayList<Map<String, Object>>();
          for (int i = 0; i < inputs.size(); i++)
            rows.add(obj("index", i, "embedding", Collections.nCopies(1024, 0.2)));
          byte[] body = encode(obj("data", rows)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      config
          .section("llm")
          .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      config.section("llm").put("apiKey", "test-key");
      var provider = new QwenProvider(config);
      assertEquals(23, provider.embeddings(Collections.nCopies(23, "真实字符串输入")).size());
      assertEquals(3, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void retrievalRanksAndScopedCandidates() {
    String kb = "kb_" + alice;
    var uploaded =
        knowledge.upload(
            alice,
            kb,
            "rank.md",
            "text/markdown",
            "Java 检索证据".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            obj("strategy", "paragraph"),
            false,
            obj());
    String doc = s(map(uploaded.get("document")), "id");
    var d = knowledge.get(alice, kb, doc);
    d.put("indexStatus", "indexed");
    store.put("document", alice, kb, d);
    store.db.update(
        "INSERT INTO knowledge_chunks VALUES(?,?,?,?,?,?,?)",
        "chunk-owned",
        alice,
        kb,
        doc,
        "Java 检索证据",
        "rank.md",
        "{}");
    store.db.update(
        "INSERT INTO knowledge_chunks VALUES(?,?,?,?,?,?,?)",
        "chunk-foreign",
        bob,
        "kb_" + bob,
        "foreign-doc",
        "Java 私有信息",
        "private.md",
        "{}");
    doReturn(List.of(Collections.nCopies(1024, 0.1f))).when(model).embeddings(anyList());
    when(vector.search(eq(alice), eq(kb), anyList()))
        .thenReturn(List.of(obj("id", "chunk-owned", "score", 0.8)));
    doReturn(List.of(obj("index", 0, "relevance_score", 0.95)))
        .when(model)
        .rerank(anyString(), anyList());
    var refs = knowledge.retrieve(alice, "Java");
    assertEquals(1, refs.size());
    for (String key :
        List.of(
            "vectorRank",
            "bm25Rank",
            "rerankRank",
            "vectorScore",
            "bm25Score",
            "rrfScore",
            "rerankScore")) assertNotNull(refs.get(0).get(key));
    assertEquals(doc, refs.get(0).get("documentId"));
    assertFalse(s(refs.get(0), "content").isBlank());
    verify(model).rerank(eq("Java"), argThat(v -> v.size() == 1 && !v.get(0).contains("私有")));
  }

  @Test
  void retrievalFiltersBelowRerankMinScore() {
    String kb = "kb_" + alice;
    var uploaded =
        knowledge.upload(
            alice,
            kb,
            "score.md",
            "text/markdown",
            "Java 检索证据".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            obj("strategy", "paragraph"),
            false,
            obj());
    String doc = s(map(uploaded.get("document")), "id");
    var d = knowledge.get(alice, kb, doc);
    d.put("indexStatus", "indexed");
    store.put("document", alice, kb, d);
    store.db.update(
        "INSERT INTO knowledge_chunks VALUES(?,?,?,?,?,?,?)",
        "chunk-strong",
        alice,
        kb,
        doc,
        "Java Java Java 网关熔断处理",
        "score.md",
        "{}");
    store.db.update(
        "INSERT INTO knowledge_chunks VALUES(?,?,?,?,?,?,?)",
        "chunk-weak",
        alice,
        kb,
        doc,
        "无关内容块",
        "score.md",
        "{}");
    doReturn(List.of(Collections.nCopies(1024, 0.1f))).when(model).embeddings(anyList());
    when(vector.search(eq(alice), eq(kb), anyList()))
        .thenReturn(
            List.of(
                obj("id", "chunk-weak", "score", 0.8), obj("id", "chunk-strong", "score", 0.7)));
    doReturn(
            List.of(obj("index", 0, "relevance_score", 0.91), obj("index", 1, "relevance_score", 0.40)))
        .when(model)
        .rerank(anyString(), anyList());
    config.section("llm").put("rerankMinScore", 0.6);
    try {
      var refs = knowledge.retrieve(alice, "Java");
      assertEquals(1, refs.size());
      assertEquals("chunk-strong", refs.get(0).get("chunkId"));
      assertEquals(1, refs.get(0).get("rerankRank"));
      doReturn(
              List.of(obj("index", 0, "relevance_score", 0.30), obj("index", 1, "relevance_score", 0.45)))
          .when(model)
          .rerank(anyString(), anyList());
      assertTrue(knowledge.retrieve(alice, "Java").isEmpty());
    } finally {
      config.section("llm").remove("rerankMinScore");
    }
  }

  @Test
  void successDiagnosisProvenanceAndAutomaticCase() throws Exception {
    var remote =
        obj(
            "name",
            "sls_query_logs",
            "description",
            "日志查询",
            "inputSchema",
            obj("type", "object", "properties", obj()),
            "connection",
            obj());
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", remote));
    when(mcp.call(anyMap(), anyMap()))
        .thenReturn(
            encode(
                obj(
                    "content",
                    List.of(
                        obj(
                            "type",
                            "text",
                            "text",
                            "[{\"message\":\"connection pool exhausted\"}]")))));
    doAnswer(
            inv -> {
              String prompt = inv.getArgument(0), input = inv.getArgument(1);
              if (prompt.contains("规划器")) {
                return input.contains("connection pool exhausted")
                    ? "{\"steps\":[]}"
                    : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{},\"purpose\":\"查询日志\"}]}";
              }
              return "{\"title\":\"连接池诊断\",\"content\":\"# 报告\\n"
                         + "依据真实工具边界证据确认连接池耗尽\",\"rootCause\":\"连接池耗尽\",\"summary\":\"连接池诊断\",\"remediation\":\"检查慢查询\",\"keywords\":[\"连接池\"]}";
            })
        .when(model)
        .answer(anyString(), anyString());
    doAnswer(
            inv ->
                Collections.nCopies(
                    ((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model)
        .embeddings(anyList());
    var task = diag.create(alice, obj("query", "连接池告警"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("succeeded"));
    await(() -> !diag.cases(alice).isEmpty());
    var chain = diag.chain(alice, id);
    assertEquals(1, maps(chain.get("reports")).size());
    assertEquals(1, maps(chain.get("reportEvidenceLinks")).size());
    assertTrue(maps(chain.get("checkpoints")).size() >= 3);
    assertTrue(maps(chain.get("steps")).stream().anyMatch(x -> s(x, "phase").equals("replanner")));
    var c = diag.cases(alice).get(0);
    assertEquals(1, ((List<?>) c.get("evidenceIds")).size());
    assertEquals(c.get("id"), diag.persistCase(alice, id).get("id"));
    assertThrows(ApiException.class, () -> diag.persistCase(bob, id));
  }

  @Test
  void diagnosisSkillsInjectSopIntoPlannerAndReportAndValidateOwnership() throws Exception {
    var remote =
        obj(
            "name",
            "sls_query_logs",
            "description",
            "日志查询",
            "inputSchema",
            obj("type", "object", "properties", obj()),
            "connection",
            obj());
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", remote));
    when(mcp.call(anyMap(), anyMap()))
        .thenReturn(
            encode(
                obj(
                    "content",
                    List.of(
                        obj(
                            "type",
                            "text",
                            "text",
                            "[{\"message\":\"connection pool exhausted\"}]")))));
    var plannerPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var reportPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var filterPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    doAnswer(
            inv -> {
              String prompt = inv.getArgument(0), input = inv.getArgument(1);
              if (prompt.contains("Skill筛选器")) {
                filterPrompts.add(prompt);
                return "{\"skillIds\":[]}";
              }
              if (prompt.contains("规划器")) {
                plannerPrompts.add(prompt);
                return input.contains("connection pool exhausted")
                    ? "{\"steps\":[]}"
                    : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{},\"purpose\":\"查询日志\"}]}";
              }
              reportPrompts.add(prompt);
              return "{\"title\":\"连接池诊断\",\"content\":\"# 报告\\n"
                         + "依据真实工具边界证据确认连接池耗尽\",\"rootCause\":\"连接池耗尽\",\"summary\":\"连接池诊断\",\"remediation\":\"检查慢查询\",\"keywords\":[\"连接池\"]}";
            })
        .when(model)
        .answer(anyString(), anyString());
    doAnswer(
            inv ->
                Collections.nCopies(
                    ((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model)
        .embeddings(anyList());
    var skill =
        assets.skill(
            alice,
            "SKILL.md",
            "---\nname: sls-sop\ndescription: SLS 排查 SOP\n---\n\n# 排查步骤\n\n先查错误率，再查连接池指标。\n");
    var foreign =
        assets.skill(
            bob, "SKILL.md", "---\nname: bob-sop\ndescription: Bob 的 SOP\n---\n\nBob 的排查步骤。\n");
    assertThrows(
        ApiException.class,
        () -> diag.create(alice, obj("query", "q", "skillIds", List.of(s(foreign, "id")))));
    assertThrows(
        ApiException.class,
        () -> diag.create(alice, obj("query", "q", "skillIds", List.of("skill_missing"))));
    assertThrows(
        ApiException.class,
        () ->
            diag.create(
                alice,
                obj(
                    "query",
                    "q",
                    "skillIds",
                    List.of("skill_a", "skill_b", "skill_c", "skill_d"))));
    assertThrows(
        ApiException.class,
        () -> diag.create(alice, obj("query", "q", "skillIds", List.of(s(skill, "id"), s(skill, "id")))));
    var task =
        diag.create(alice, obj("query", "连接池告警", "skillIds", List.of(s(skill, "id"))));
    String id = s(task, "id");
    assertEquals(List.of(s(skill, "id")), task.get("skillIds"));
    await(() -> s(diag.get(alice, id), "status").equals("succeeded"));
    assertEquals(2, plannerPrompts.size());
    for (String prompt : plannerPrompts) {
      assertTrue(prompt.contains("Skill排查指引"));
      assertTrue(prompt.contains("【Skill sls-sop】"));
      assertTrue(prompt.contains("先查错误率，再查连接池指标。"));
      assertFalse(prompt.contains("description: SLS 排查 SOP"));
    }
    assertEquals(1, reportPrompts.size());
    assertTrue(reportPrompts.get(0).contains("Skill指引"));
    assertTrue(reportPrompts.get(0).contains("先查错误率，再查连接池指标。"));
    var report = maps(diag.chain(alice, id).get("reports")).get(0);
    assertEquals(List.of(s(skill, "id")), map(report.get("payload")).get("skillIds"));
    var plain = diag.create(alice, obj("query", "普通诊断"));
    await(() -> s(diag.get(alice, s(plain, "id")), "status").equals("succeeded"));
    assertEquals(4, plannerPrompts.size());
    assertEquals(2, reportPrompts.size());
    assertEquals(1, filterPrompts.size());
    for (String prompt : plannerPrompts.subList(2, 4))
      assertFalse(prompt.contains("Skill排查指引"));
    assertFalse(reportPrompts.get(1).contains("Skill指引"));
    var plainReport = maps(diag.chain(alice, s(plain, "id")).get("reports")).get(0);
    assertFalse(map(plainReport.get("payload")).containsKey("skillIds"));
  }

  @Test
  void diagnosisAutoSelectsSkillsFromCatalogWhenNoneChosen() throws Exception {
    var remote =
        obj(
            "name",
            "sls_query_logs",
            "description",
            "日志查询",
            "inputSchema",
            obj("type", "object", "properties", obj()),
            "connection",
            obj());
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", remote));
    when(mcp.call(anyMap(), anyMap()))
        .thenReturn(
            encode(
                obj(
                    "content",
                    List.of(
                        obj(
                            "type",
                            "text",
                            "text",
                            "[{\"message\":\"connection pool exhausted\"}]")))));
    var filterPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var plannerPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var reportPrompts = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var first =
        assets.skill(
            alice,
            "SKILL.md",
            "---\nname: sls-sop\ndescription: SLS 日志排查 SOP\n---\n\n# 排查步骤\n\n先查错误率，再查连接池指标。\n");
    var second =
        assets.skill(
            alice,
            "SKILL.md",
            "---\nname: pool-sop\ndescription: 连接池排查手册\n---\n\n# 连接池\n\n观察连接池使用率曲线。\n");
    String firstId = s(first, "id"), secondId = s(second, "id");
    doAnswer(
            inv -> {
              String prompt = inv.getArgument(0), input = inv.getArgument(1);
              if (prompt.contains("Skill筛选器")) {
                filterPrompts.add(prompt + "\n" + input);
                return "{\"skillIds\":[\"skill_unknown\",\""
                    + firstId
                    + "\",\""
                    + firstId
                    + "\",\""
                    + secondId
                    + "\"]}";
              }
              if (prompt.contains("规划器")) {
                plannerPrompts.add(prompt);
                return input.contains("connection pool exhausted")
                    ? "{\"steps\":[]}"
                    : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{},\"purpose\":\"查询日志\"}]}";
              }
              reportPrompts.add(prompt);
              return "{\"title\":\"连接池诊断\",\"content\":\"# 报告\\n"
                         + "依据真实工具边界证据确认连接池耗尽\",\"rootCause\":\"连接池耗尽\",\"summary\":\"连接池诊断\",\"remediation\":\"检查慢查询\",\"keywords\":[\"连接池\"]}";
            })
        .when(model)
        .answer(anyString(), anyString());
    doAnswer(
            inv ->
                Collections.nCopies(
                    ((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model)
        .embeddings(anyList());
    var task = diag.create(alice, obj("query", "连接池告警"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("succeeded"));
    assertEquals(1, filterPrompts.size());
    assertTrue(filterPrompts.get(0).contains("sls-sop"));
    assertTrue(filterPrompts.get(0).contains("连接池排查手册"));
    assertEquals(2, plannerPrompts.size());
    for (String prompt : plannerPrompts) {
      assertTrue(prompt.contains("Skill排查指引"));
      assertTrue(prompt.contains("【Skill sls-sop】"));
      assertTrue(prompt.contains("先查错误率，再查连接池指标。"));
      assertTrue(prompt.contains("【Skill pool-sop】"));
      assertTrue(prompt.contains("观察连接池使用率曲线。"));
    }
    assertEquals(1, reportPrompts.size());
    assertTrue(reportPrompts.get(0).contains("Skill指引"));
    assertTrue(reportPrompts.get(0).contains("观察连接池使用率曲线。"));
    assertEquals(List.of(firstId, secondId), diag.get(alice, id).get("autoSkillIds"));
    var report = maps(diag.chain(alice, id).get("reports")).get(0);
    assertEquals(List.of(firstId, secondId), map(report.get("payload")).get("skillIds"));
  }

  @Test
  void skillContentEndpointReturnsBodyAndEnforcesOwnership() throws Exception {
    var skill =
        assets.skill(
            alice,
            "SKILL.md",
            "---\nname: sls-sop\ndescription: SLS 排查 SOP\n---\n\n# 排查步骤\n\n先查错误率，再查连接池指标。\n");
    mvc.perform(get("/chat/skills/" + s(skill, "id")).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("sls-sop"))
        .andExpect(
            jsonPath("$.data.content")
                .value(org.hamcrest.Matchers.containsString("先查错误率，再查连接池指标。")));
    mvc.perform(get("/chat/skills/" + s(skill, "id")).header("Authorization", "Bearer " + btoken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/chat/skills/skill_missing").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void emptyLogDiagnosisPersistsFailureReportAndDoesNotRetryOrCreateCase() throws Exception {
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs")));
    when(mcp.call(anyMap(), anyMap())).thenReturn(encode(obj("content", List.of(obj("text", "[]")))));
    doAnswer(inv -> maps(decode(inv.getArgument(1)).get("evidence")).stream()
        .anyMatch(e -> s(e, "kind").equals("log"))
        ? "{\"steps\":[]}"
        : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{}}]}")
        .when(model).answer(contains("规划器"), anyString());
    var task = diag.create(alice, obj("query", "aaa"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("failed"));
    var chain = diag.chain(alice, id);
    var report = maps(chain.get("reports")).get(0);
    assertTrue(s(report, "content").contains("没有匹配日志"));
    assertEquals(1, maps(chain.get("reportEvidenceLinks")).size());
    assertEquals(1, n(jobs.resource(alice, id).get("attempt")));
    assertTrue(diag.cases(alice).isEmpty());
    new DiagnosisService(store, jobs, model, mcp, knowledge);
    assertEquals(1, maps(diag.chain(alice, id).get("reports")).size());
    assertThrows(ApiException.class, () -> diag.persistCase(alice, id));
  }

  @Test
  void retryFailedDiagnosisExecutesFreshPlanAndRestartKeepsLatestSuccess() throws Exception {
    var retrying = new java.util.concurrent.atomic.AtomicBoolean(false);
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs")));
    when(mcp.call(anyMap(), anyMap()))
        .thenReturn(encode(obj("content", List.of(obj("text", "[]")))))
        .thenReturn(encode(obj("content", List.of(obj("text", "[{\"message\":\"recovered-log\"}]")))));
    doAnswer(inv -> {
      String prompt = inv.getArgument(0), input = inv.getArgument(1);
      if (prompt.contains("规划器")) return input.contains("recovered-log") ||
          (input.contains("\"evidence\":[") && input.contains("\"source\":\"sls_query_logs\"") && !retrying.get())
          ? "{\"steps\":[]}"
          : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{}}]}";
      return "{\"title\":\"重试诊断\",\"content\":\"# 告警分析报告\\n真实日志已保存\",\"rootCause\":\"未确认\"}";
    }).when(model).answer(anyString(), anyString());
    doAnswer(inv -> Collections.nCopies(((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model).embeddings(anyList());
    var task = diag.create(alice, obj("query", "诊断并重试"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("failed"));
    var previous = jobs.resource(alice, id);
    retrying.set(true);
    jobs.retry(alice, s(previous, "id"));
    await(() -> s(diag.get(alice, id), "status").equals("succeeded"));
    assertEquals(2, maps(diag.chain(alice, id).get("reports")).size());
    verify(mcp, times(2)).call(anyMap(), anyMap());
    new DiagnosisService(store, jobs, model, mcp, knowledge);
    assertEquals("succeeded", s(diag.get(alice, id), "status"));
  }

  @Test
  void unresolvedPlaceholderIsNotSentAndFailureDetailsArePersisted() throws Exception {
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs")));
    doReturn("{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{\"query\":\"待替换为生成的查询\"}}]}")
        .when(model).answer(contains("规划器"), anyString());
    var task = diag.create(alice, obj("query", "查日志"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("failed"));
    verify(mcp, never()).call(anyMap(), anyMap());
    var chain = diag.chain(alice, id);
    assertTrue(maps(chain.get("toolCalls")).stream().anyMatch(a -> s(a, "errorMessage").contains("占位值")));
    assertTrue(s(maps(chain.get("reports")).get(0), "content").contains("未发送查询"));
    assertTrue(s(jobs.resource(alice, id), "errorMessage").contains("占位值"));
    assertTrue(diag.cases(alice).isEmpty());
  }

  @Test
  void diagnosisKeepsSafeMcpErrorCauseInAuditAndReport() throws Exception {
    config.section("llm").put("apiKey", "test-secret-value");
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs")));
    when(mcp.call(anyMap(), anyMap())).thenThrow(new IllegalStateException("MCP 工具执行失败",
        new IllegalStateException("SLS InvalidParameter.query test-secret-value")));
    doReturn("{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{}}]}")
        .when(model).answer(contains("规划器"), anyString());
    var task = diag.create(alice, obj("query", "查日志"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("failed"));
    String chain = encode(diag.chain(alice, id));
    assertTrue(chain.contains("InvalidParameter.query"));
    assertTrue(chain.contains("[redacted]"));
    assertFalse(chain.contains("test-secret-value"));
    assertTrue(jobs.events(alice, s(jobs.resource(alice, id), "id"), 0).toString().contains("InvalidParameter.query"));
  }

  @Test
  void activeAlertProtocolsAndFailure() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/alerts",
        exchange -> {
          byte[] body =
              encode(
                      List.of(
                          obj(
                              "labels",
                              obj("alertname", "PoolExhausted", "service", "order"),
                              "annotations",
                              obj("summary", "连接池耗尽"),
                              "status",
                              obj("state", "active"),
                              "startsAt",
                              now()),
                          obj(
                              "labels",
                              obj("alertname", "Resolved"),
                              "status",
                              obj("state", "suppressed"))))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      config.data.put(
          "prometheusAlerts",
          obj(
              "sources",
              List.of(
                  obj(
                      "id",
                      "local",
                      "type",
                      "alertmanager-v2",
                      "alertsApi",
                      "http://127.0.0.1:" + server.getAddress().getPort() + "/alerts"))));
      var alerts = new AlertService(config, new SunfireAlertStore(store)).active();
      assertEquals(1, alerts.size());
      assertEquals("PoolExhausted", alerts.get(0).get("alertName"));
    } finally {
      server.stop(0);
    }
    assertThrows(
        ApiException.class, () -> new AlertService(config, new SunfireAlertStore(store)).active());
  }

  @Test
  void sunfireCallbackTokenDedupeExpiryAndMerge() throws Exception {
    config.data.put("prometheusAlerts", obj("sources", List.of()));
    mvc.perform(post("/aiops/alerts/sunfire").param("token", "x").content(encode(obj())))
        .andExpect(status().isNotFound());

    config.data.put(
        "prometheusAlerts", obj("sources", List.of(), "sunfireCallbackToken", "st-token-1"));
    String payload =
        encode(
            obj(
                "alarmId",
                "alarm-1",
                "alarmTime",
                "1753081140000",
                "level",
                "Critical",
                "appName",
                "buy2",
                "ruleName",
                "失败数大于500",
                "ruleLink",
                "https://sunfire.example.com/alarmConfig/detail/213255980",
                "content",
                "失败数 [当前值为: 642] > 500",
                "timeSeriesContexts",
                List.of(
                    obj(
                        "alarmMsg",
                        "失败数 [当前值为: 642] > 500",
                        "tags",
                        obj(
                            "tags",
                            List.of(obj("name", "服务", "value", "com.alibaba.sunfire.User")))))));
    mvc.perform(
            post("/aiops/alerts/sunfire")
                .param("token", "st-token-1")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());
    mvc.perform(
            post("/aiops/alerts/sunfire")
                .param("token", "wrong")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isUnauthorized());

    var live = new AlertService(config, new SunfireAlertStore(store)).active();
    assertEquals(1, live.size());
    var alert = live.get(0);
    assertEquals("sunfire", s(alert, "source"));
    assertEquals("alarm-1", s(alert, "id"));
    assertEquals("失败数大于500", s(alert, "alertName"));
    assertEquals("buy2", s(alert, "service"));
    assertEquals("critical", s(alert, "severity"));
    assertEquals("firing", s(alert, "status"));
    assertEquals(Instant.ofEpochMilli(1753081140000L).toString(), s(alert, "startsAt"));
    assertEquals("com.alibaba.sunfire.User", s(map(alert.get("labels")), "服务"));
    assertEquals(
        "https://sunfire.example.com/alarmConfig/detail/213255980",
        s(map(alert.get("annotations")), "ruleLink"));

    mvc.perform(
            post("/aiops/alerts/sunfire")
                .param("token", "st-token-1")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isOk());
    assertEquals(1, new SunfireAlertStore(store).active().size());

    store.db.update(
        "UPDATE sunfire_alerts SET expires_at=?", Instant.now().minusSeconds(60).toString());
    assertTrue(new SunfireAlertStore(store).active().isEmpty());
  }

  @Test
  void sunfirePollerIngestsDeliveredAlarmsDedupesByRuleAndSkipsSuppressed() {
    config.data.put(
        "prometheusAlerts",
        obj(
            "sources",
            List.of(),
            "sunfirePoll",
            obj("enabled", "true", "apps", List.of("uniagent-control-center"), "ownerId", alice)));
    when(mcp.tools(anyString())).thenReturn(Map.of("queryAlarmList", obj("name", "queryAlarmList")));
    Map<String, Object> delivered =
        obj(
            "alarmId",
            "34eef852496d4bb69309d6f71cdcfd1f",
            "alarmRuleId",
            "220696878",
            "alarmTime",
            1795578060000L,
            "appName",
            "uniagent-control-center",
            "content",
            "共有100台机器[cpu利用率]异常，摘要：\n* [33.5.186.193] cpu利用率 [当前值为: 41.000%] > 20%",
            "errorMsg",
            "报警统计：持续13分，已发送4条，被抑制9条",
            "level",
            "warning",
            "pluginName",
            "uniagent-control-center[cpu利用率]",
            "ruleName",
            "yiantest",
            "tenantName",
            "应用监控");
    Map<String, Object> suppressed =
        obj(
            "alarmId",
            "b839844a643c4f4b8e71826526d4d77c",
            "alarmRuleId",
            "220240375",
            "alarmTime",
            1795578000000L,
            "appName",
            "uniagent-control-center",
            "content",
            "共有1条数据触发[warning]报警",
            "errorMsg",
            "[报警抑制] 报警未被订阅",
            "level",
            "critical",
            "ruleName",
            "F88商家创单失败监控");
    when(mcp.call(any(), any()))
        .thenReturn(
            encode(
                obj(
                    "content",
                    List.of(obj("type", "text", "text", encode(List.of(delivered, suppressed)))),
                    "isError",
                    false)));
    var sunfireStore = new SunfireAlertStore(store);
    var poller = new SunfireAlertPoller(config, mcp, sunfireStore);

    poller.pollRound();

    var active = sunfireStore.active();
    assertEquals(1, active.size());
    var alert = active.get(0);
    assertEquals("220696878", s(alert, "id"));
    assertEquals("sunfire", s(alert, "source"));
    assertEquals("yiantest", s(alert, "alertName"));
    assertEquals("uniagent-control-center", s(alert, "service"));
    assertEquals("warning", s(alert, "severity"));
    assertEquals("firing", s(alert, "status"));
    assertEquals(Instant.ofEpochMilli(1795578060000L).toString(), s(alert, "startsAt"));
    assertEquals("34eef852496d4bb69309d6f71cdcfd1f", s(map(alert.get("context")), "latestAlarmId"));

    delivered.put("alarmId", "f6189386bd4546c78f045af35f9dbe0b");
    delivered.put("alarmTime", 1795578120000L);
    delivered.put("content", "最新一轮触发，当前值为: 45.000%");
    doReturn(
            encode(
                obj(
                    "content",
                    List.of(obj("type", "text", "text", encode(List.of(delivered)))),
                    "isError",
                    false)))
        .when(mcp)
        .call(any(), any());
    poller.pollRound();

    active = sunfireStore.active();
    assertEquals(1, active.size());
    assertEquals("最新一轮触发，当前值为: 45.000%", s(active.get(0), "summary"));
    assertEquals(
        Instant.ofEpochMilli(1795578120000L).toString(), s(active.get(0), "startsAt"));
  }

  @Test
  void sunfirePollerDisabledOrFailingIsInert() {
    config.data.put("prometheusAlerts", obj("sources", List.of()));
    var sunfireStore = new SunfireAlertStore(store);
    var poller = new SunfireAlertPoller(config, mcp, sunfireStore);

    poller.pollRound();
    verify(mcp, never()).tools(anyString());

    config.data.put(
        "prometheusAlerts",
        obj("sources", List.of(), "sunfirePoll", obj("enabled", "true", "apps", List.of("buy2"))));
    poller.pollRound();
    verify(mcp, never()).tools(anyString());

    config.data.put(
        "prometheusAlerts",
        obj(
            "sources",
            List.of(),
            "sunfirePoll",
            obj("enabled", "true", "apps", List.of("uniagent-control-center"), "ownerId", alice)));
    when(mcp.tools(anyString())).thenReturn(Map.of("queryAlarmList", obj("name", "queryAlarmList")));
    when(mcp.call(any(), any())).thenThrow(new RuntimeException("MCP 连接不可达"));
    poller.pollRound();
    assertTrue(sunfireStore.active().isEmpty());

    doReturn(
            encode(
                obj(
                    "content",
                    List.of(
                        obj(
                            "type",
                            "text",
                            "text",
                            encode(
                                List.of(
                                    obj(
                                        "alarmId",
                                        "a1",
                                        "alarmRuleId",
                                        "r1",
                                        "alarmTime",
                                        1795578060000L,
                                        "appName",
                                        "uniagent-control-center",
                                        "content",
                                        "cpu 异常",
                                        "level",
                                        "urgent",
                                        "ruleName",
                                        "yiantest"))))),
                    "isError",
                    false)))
        .when(mcp)
        .call(any(), any());
    poller.pollRound();
    assertEquals(1, sunfireStore.active().size());
    assertEquals("urgent", s(sunfireStore.active().get(0), "severity"));
  }

  @Test
  void smartInspectionScansConfirmsAndRecoversWithoutCaseLanding() throws Exception {
    config.data.put(
        "inspection",
        obj(
            "ownerId",
            alice,
            "apps",
            List.of("trade"),
            "intervalMinutes",
            1,
            "windowMinutes",
            5,
            "thresholdPercent",
            30,
            "autoDiagnose",
            true));
    store.put(
        "inspection_metric",
        alice,
        "",
        obj(
            "id",
            "trade",
            "service",
            "trade",
            "metrics",
            List.of(
                obj(
                    "name",
                    "cpu",
                    "promql",
                    "avg(pod_cpu_limit_usage{app_name=\"{app}\"})",
                    "unit",
                    "%",
                    "upGate",
                    null,
                    "downGate",
                    null,
                    "thresholdPercent",
                    null))));
    mvc.perform(get("/aiops/inspection/settings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.config.configured").value(true))
        .andExpect(jsonPath("$.data.updatedAt").value(""))
        .andExpect(jsonPath("$.data.availableCategories.length()").value(7))
        .andExpect(jsonPath("$.data.catalog.length()").value(20))
        .andExpect(jsonPath("$.data.metrics").doesNotExist());
    mvc.perform(
            put("/aiops/inspection/settings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(encode(obj("enabled", true, "metrics", List.of("cpu")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.metrics.length()").value(1));
    mvc.perform(
            put("/aiops/inspection/settings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(encode(obj("missing", true))))
        .andExpect(status().isBadRequest());

    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());

    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 50.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());

    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 50.0, 52.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    var findings = store.list("inspection_finding", alice, null);
    assertEquals(1, findings.size());
    assertEquals("open", s(findings.get(0), "status"));
    assertEquals("up", s(findings.get(0), "direction"));
    assertEquals("cpu", s(findings.get(0), "metricKey"));
    var diagnosticId = s(findings.get(0), "diagnosticId");
    assertFalse(diagnosticId.isBlank());
    assertEquals(Boolean.FALSE, diag.get(alice, diagnosticId).get("caseEnabled"));

    inspection.runRound();
    findings = store.list("inspection_finding", alice, null);
    assertEquals(1, findings.size());
    assertEquals(diagnosticId, s(findings.get(0), "diagnosticId"));

    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.2)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    findings = store.list("inspection_finding", alice, null);
    assertEquals("recovered", s(findings.get(0), "status"));
    assertFalse(s(findings.get(0), "recoveredAt").isBlank());

    mvc.perform(get("/aiops/inspection/findings").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.items[0].status").value("recovered"));
    mvc.perform(get("/aiops/inspection/findings").header("Authorization", "Bearer " + btoken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(0));

    mvc.perform(
            put("/aiops/inspection/settings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(encode(obj("enabled", false))))
        .andExpect(status().isOk());
    clearInvocations(mcp);
    inspection.tick();
    verify(mcp, never()).call(any(), any());
  }

  @Test
  void impactGateRequiresAbsoluteWatermarkBeforeConfirmingFinding() throws Exception {
    config.data.put(
        "inspection",
        obj(
            "ownerId",
            alice,
            "apps",
            List.of("trade"),
            "intervalMinutes",
            1,
            "windowMinutes",
            5,
            "thresholdPercent",
            30,
            "autoDiagnose",
            false));
    store.put(
        "inspection_metric",
        alice,
        "",
        obj(
            "id",
            "trade",
            "service",
            "trade",
            "metrics",
            List.of(
                obj(
                    "name",
                    "cpu",
                    "promql",
                    "avg(pod_cpu_limit_usage{app_name=\"{app}\"})",
                    "unit",
                    "%",
                    "upGate",
                    50,
                    "downGate",
                    20,
                    "thresholdPercent",
                    null))));
    mvc.perform(
            put("/aiops/inspection/settings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(encode(obj("enabled", true, "metrics", List.of("cpu")))))
        .andExpect(status().isOk());

    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());

    // 偏移 +182.5% 过线但当前值 28.25 未达 upGate 50：连续两轮都不建候选
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 26.0, 28.0, 30.0, 29.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());
    assertEquals(0, store.list("inspection_scan_state", alice, null).size());

    // 当前值 53.75 过双门：第一轮建状态，第二轮同向确认
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 50.0, 52.0, 55.0, 58.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());
    inspection.runRound();
    var findings = store.list("inspection_finding", alice, null);
    assertEquals(1, findings.size());
    assertEquals("open", s(findings.get(0), "status"));
    assertEquals(437.5, (double) findings.get(0).get("deltaPercent"));

    // down 方向回落到 30（> downGate 20）：双门不过，open 发现走恢复
    doReturn(series(List.of(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 30.0, 30.0, 30.0, 30.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals("recovered", s(store.list("inspection_finding", alice, null).get(0), "status"));

    // down 方向降到 15（≤ downGate 20）：第一轮建状态，第二轮确认 down 发现
    doReturn(series(List.of(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 15.0, 15.0, 15.0, 15.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    assertEquals(1, store.list("inspection_finding", alice, null).size());
    inspection.runRound();
    findings = store.list("inspection_finding", alice, null);
    assertEquals(2, findings.size());
    var down =
        findings.stream()
            .filter(f -> s(f, "status").equals("open"))
            .findFirst()
            .orElseThrow();
    assertEquals("down", s(down, "direction"));

    // 回到偏移阈值内：恢复闭环
    doReturn(series(List.of(15.0, 15.0, 15.0, 15.0, 15.0, 15.0, 15.0, 15.0, 15.0, 15.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    findings = store.list("inspection_finding", alice, null);
    assertEquals(0, findings.stream().filter(f -> s(f, "status").equals("open")).count());
  }

  @Test
  void perMetricThresholdOverridesGlobalThreshold() throws Exception {
    config.data.put(
        "inspection", obj("ownerId", alice, "apps", List.of("trade"), "autoDiagnose", false));
    store.put(
        "inspection_metric",
        alice,
        "",
        obj(
            "id",
            "trade",
            "service",
            "trade",
            "metrics",
            List.of(
                obj(
                    "name",
                    "jvm_gc",
                    "promql",
                    "sum(jvm_gc_pause_ms{app_name=\"{app}\"})",
                    "unit",
                    "ms",
                    "upGate",
                    null,
                    "downGate",
                    null,
                    "thresholdPercent",
                    80))));
    // 勾选清单按指标名匹配：仅手工注册的 jvm_gc 参与扫描，白名单未勾选不自动补注册
    mvc.perform(
            put("/aiops/inspection/settings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(encode(obj("enabled", true, "metrics", List.of("jvm_gc")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.metrics.length()").value(1));

    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    // +50% 未达该指标自身突变线 80：连续两轮不建候选（全局 30% 线会误建）
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 15.0, 15.0, 15.0, 15.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    inspection.runRound();
    assertEquals(0, store.list("inspection_finding", alice, null).size());
    assertEquals(0, store.list("inspection_scan_state", alice, null).size());

    // +200% 过线：两轮确认注册指标的发现
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 30.0, 30.0, 30.0, 30.0)))
        .when(mcp)
        .call(any(), any());
    inspection.runRound();
    inspection.runRound();
    var findings = store.list("inspection_finding", alice, null);
    assertEquals(1, findings.size());
    assertEquals("jvm_gc", s(findings.get(0), "metricKey"));

    // 全程未发生白名单自动补注册
    var reg = map(store.get("inspection_metric", alice, "trade"));
    assertEquals(1, maps(reg.get("metrics")).size());
  }

  @Test
  void scanAutoRegistersWhitelistMetricsWithProbeAndRules() throws Exception {
    config.data.put(
        "inspection", obj("ownerId", alice, "apps", List.of("trade"), "autoDiagnose", false));
    inspection.updateSettings(obj("enabled", true));
    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    doReturn(series(List.of(120.0, 120.0, 120.0, 130.0, 120.0, 120.0, 125.0, 120.0, 120.0, 122.0)))
        .when(mcp)
        .call(any(), any());
    // 规则中杜撰名剔除，白名单内的指标获得 LLM 设定的规则
    doReturn(
            encode(
                obj(
                    "rules",
                    List.of(
                        obj(
                            "name",
                            "pod_cpu_limit_usage",
                            "upGate",
                            0.6,
                            "downGate",
                            null,
                            "thresholdPercent",
                            25,
                            "reason",
                            "CPU 突增需达到水位"),
                        obj("name", "fabricated_metric", "upGate", 1, "downGate", null, "thresholdPercent", 30, "reason", "杜撰")))))
        .when(model)
        .answer(anyString(), anyString());

    inspection.runRound();

    var reg = map(store.get("inspection_metric", alice, "trade"));
    var metrics = maps(reg.get("metrics"));
    assertEquals(20, metrics.size());
    var cpu =
        metrics.stream()
            .filter(m -> s(m, "name").equals("pod_cpu_limit_usage"))
            .findFirst()
            .orElseThrow();
    assertEquals("基础", s(cpu, "category"));
    assertEquals("avg(pod_cpu_limit_usage{app_name=\"trade\"})", s(cpu, "promql"));
    assertEquals(0.6, (double) cpu.get("upGate"));
    assertEquals(25.0, (double) cpu.get("thresholdPercent"));
    assertTrue(metrics.stream().noneMatch(m -> s(m, "name").equals("fabricated_metric")));
    // 未获规则的指标保持 null（回落全局阈值）
    var heap =
        metrics.stream()
            .filter(m -> s(m, "name").equals("jvm_mem_heap_usage"))
            .findFirst()
            .orElseThrow();
    assertNull(heap.get("upGate"));
    assertNull(heap.get("thresholdPercent"));

    // 幂等：已注册后不再探针、不再调用 LLM，仅扫描
    clearInvocations(mcp, model);
    inspection.runRound();
    verify(model, never()).answer(anyString(), anyString());
    verify(mcp, times(20)).call(any(), any());
  }

  @Test
  void autoRegistrationDegradesToGlobalThresholdWhenRulesFail() throws Exception {
    config.data.put(
        "inspection", obj("ownerId", alice, "apps", List.of("trade"), "autoDiagnose", false));
    inspection.updateSettings(obj("enabled", true));
    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 30.0, 30.0, 30.0, 30.0)))
        .when(mcp)
        .call(any(), any());
    doThrow(new IllegalStateException("模型不可用")).when(model).answer(anyString(), anyString());

    inspection.runRound();

    // LLM 失败降级：全部白名单指标仍注册，规则保持 null（回落全局阈值）
    var reg = map(store.get("inspection_metric", alice, "trade"));
    var metrics = maps(reg.get("metrics"));
    assertEquals(20, metrics.size());
    assertEquals("pod_cpu_limit_usage", s(metrics.get(0), "name"));
    assertNull(metrics.get(0).get("upGate"));
    assertNull(metrics.get(0).get("thresholdPercent"));

    // 降级后扫描仍可用全局阈值 30% 确认发现（+200%）
    inspection.runRound();
    var findings = store.list("inspection_finding", alice, null);
    assertEquals(20, findings.size());
    assertTrue(findings.stream().allMatch(f -> s(f, "direction").equals("up")));
  }

  @Test
  void metricSelectionFiltersScanAndRecoversExcludedFindings() throws Exception {
    config.data.put(
        "inspection", obj("ownerId", alice, "apps", List.of("trade"), "autoDiagnose", false));
    doReturn(Map.of("queryMetricsByPromQL", obj("name", "queryMetricsByPromQL")))
        .when(mcp)
        .tools(alice);
    doReturn(series(List.of(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 50.0, 50.0, 50.0, 50.0)))
        .when(mcp)
        .call(any(), any());
    doThrow(new IllegalStateException("模型不可用")).when(model).answer(anyString(), anyString());

    inspection.updateSettings(
        obj(
            "enabled",
            true,
            "metrics",
            List.of("jvm_mem_heap_usage", "jvm_gc_fgc_count", "jvm_thread_deadlock_count")));
    inspection.runRound();

    // 自动注册只注册勾选指标（JVM 3 条）
    var reg = map(store.get("inspection_metric", alice, "trade"));
    var metrics = maps(reg.get("metrics"));
    assertEquals(3, metrics.size());
    assertTrue(
        metrics.stream()
            .allMatch(
                m ->
                    List.of("jvm_mem_heap_usage", "jvm_gc_fgc_count", "jvm_thread_deadlock_count")
                        .contains(s(m, "name"))));

    // 两轮确认勾选指标的发现
    inspection.runRound();
    var findings = store.list("inspection_finding", alice, null);
    assertEquals(3, findings.size());
    assertTrue(findings.stream().allMatch(f -> s(f, "status").equals("open")));

    // 收窄到缓存 2 条：勾选清单外的 open 发现强制恢复
    inspection.updateSettings(
        obj(
            "enabled",
            true,
            "metrics",
            List.of("middleware_tair_read_hit_rate", "middleware_tair_read_success_rate")));
    findings = store.list("inspection_finding", alice, null);
    assertEquals(3, findings.size());
    assertTrue(findings.stream().allMatch(f -> s(f, "status").equals("recovered")));

    // 下一轮扫描只补注册缓存指标（2 条）
    clearInvocations(mcp, model);
    inspection.runRound();
    reg = map(store.get("inspection_metric", alice, "trade"));
    metrics = maps(reg.get("metrics"));
    assertEquals(5, metrics.size());
    verify(mcp, times(4)).call(any(), any()); // 2 次探针 + 2 次扫描
    verify(model, times(1)).answer(anyString(), anyString());
    inspection.runRound();
    findings = store.list("inspection_finding", alice, null);
    assertEquals(2, findings.stream().filter(f -> s(f, "status").equals("open")).count());
    assertEquals(3, findings.stream().filter(f -> s(f, "status").equals("recovered")).count());

    // 空数组=全不检：不探针、不扫描、不调用 LLM
    inspection.updateSettings(obj("enabled", true, "metrics", List.of()));
    clearInvocations(mcp, model);
    inspection.tick();
    verify(mcp, never()).call(any(), any());
    verify(model, never()).answer(anyString(), anyString());
  }

  @Test
  void mcpBuiltinConnectionsMergeWithoutBlockingRegisteredTools() {
    // 内置连接不可达时容错跳过，不阻塞已注册工具
    config.data.put(
        "mcp",
        obj(
            "builtin",
            List.of(
                obj(
                    "name",
                    "sunfire",
                    "enabled",
                    true,
                    "transport",
                    "streamable_http",
                    "url",
                    "http://127.0.0.1:9/mcp",
                    "timeoutSeconds",
                    1,
                    "retries",
                    0,
                    "headers",
                    obj("Authorization", "Bearer test")))));
    var mcpService = new McpService(store, config);
    assertEquals(Map.of(), mcpService.tools(alice));
  }

  private String series(List<Double> values) {
    var fmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    var now = Instant.now();
    var points = new ArrayList<List<String>>();
    for (int i = 0; i < values.size(); i++)
      points.add(
          List.of(
              fmt.format(now.minusSeconds((values.size() - i) * 60L)),
              String.valueOf(values.get(i))));
    return encode(
        obj(
            "content",
            List.of(
                obj(
                    "type",
                    "text",
                    "text",
                    encode(
                        obj(
                            "data",
                            List.of(
                                obj(
                                    "metric",
                                    obj("__name__", "pod_cpu_limit_usage"),
                                    "values",
                                    points))))))));
  }

  interface Check {
    boolean ready();
  }

  private void await(Check check) throws Exception {
    long end = System.nanoTime() + 15_000_000_000L;
    while (!check.ready() && System.nanoTime() < end) Thread.sleep(100);
    assertTrue(check.ready());
  }

  @Test
  void exhaustedLeasePublishesTerminalStateAndEvent() throws Exception {
    AtomicInteger terminal = new AtomicInteger();
    jobs.listeners.add(
        j -> {
          if (s(j, "status").equals("failed")) terminal.incrementAndGet();
        });
    var job = jobs.enqueue(alice, "unregistered", "expired", obj(), null);
    store.db.update(
        "UPDATE background_jobs SET"
            + " status='running',attempt=max_attempts,lease_owner='dead',lease_expires_at=? WHERE"
            + " id=?",
        Instant.now().minusSeconds(1).toString(),
        job.get("id"));
    await(() -> s(jobs.get(alice, s(job, "id")), "status").equals("failed") && terminal.get() == 1);
    assertNotNull(jobs.get(alice, s(job, "id")).get("completedAt"));
    assertEquals("error", s(jobs.events(alice, s(job, "id"), 0).get(0), "type"));
  }

  @Test
  void retrievalFiltersTokensAndOwnerConflict() {
    assertTrue(
        KnowledgeService.tokens("数据库 JavaTrace42")
            .containsAll(List.of("数", "据", "库", "数据", "据库", "javatrace42")));
    assertThrows(ApiException.class, () -> knowledge.retrieve(alice, obj("query", " ")));
    assertThrows(
        ApiException.class, () -> knowledge.retrieve(alice, obj("query", "Java", "topK", 6)));
    assertThrows(
        ApiException.class,
        () ->
            knowledge.retrieve(
                alice,
                obj("query", "Java", "filters", obj("knowledgeBaseIds", List.of("kb_" + bob)))));
    assertThrows(
        ApiException.class,
        () ->
            knowledge.retrieve(
                alice, obj("query", "Java", "filters", obj("documentIds", List.of("foreign")))));
    store.put("prompt", alice, "", obj("id", "same", "content", "Alice"));
    assertThrows(
        ApiException.class,
        () -> store.put("prompt", bob, "", obj("id", "same", "content", "Bob")));
    assertEquals("Alice", s(store.get("prompt", alice, "same"), "content"));
  }

  @Test
  void legacySaveCreatesDocumentWithoutStructuredCaseAndRejectsDuplicate() {
    String id = "manual_diagnostic";
    store.put("diagnostic", alice, "", obj("id", id, "status", "succeeded"));
    store.put(
        "report",
        alice,
        id,
        obj(
            "id",
            "manual_report",
            "title",
            "已完成报告",
            "content",
            "真实诊断正文",
            "evidenceIds",
            List.of()));
    var saved = diag.saveLegacy(alice, id);
    assertTrue(Boolean.TRUE.equals(saved.get("scheduled")));
    assertTrue(diag.cases(alice).isEmpty());
    assertThrows(ApiException.class, () -> diag.saveLegacy(alice, id));
    assertThrows(ApiException.class, () -> diag.saveLegacy(bob, id));
  }

  @Test
  void reportFailureFallsBackToActualEvidenceWithUnknownRootCause() throws Exception {
    var remote =
        obj(
            "name",
            "sls_query_logs",
            "description",
            "日志",
            "inputSchema",
            obj("type", "object", "properties", obj()),
            "connection",
            obj());
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", remote));
    when(mcp.call(anyMap(), anyMap()))
        .thenReturn(
            encode(
                obj(
                    "content",
                    List.of(obj("type", "text", "text", "[{\"message\":\"observed error\"}]")))));
    doAnswer(
            inv -> {
              String prompt = inv.getArgument(0), input = inv.getArgument(1);
              if (prompt.contains("规划器"))
                return input.contains("observed error")
                    ? "{\"steps\":[]}"
                    : "{\"steps\":[{\"tool\":\"sls_query_logs\",\"arguments\":{},\"purpose\":\"只读日志\"}]}";
              throw new IllegalStateException("报告模型不可用");
            })
        .when(model)
        .answer(anyString(), anyString());
    doAnswer(
            inv ->
                Collections.nCopies(
                    ((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model)
        .embeddings(anyList());
    var task = diag.create(alice, obj("query", "诊断开发告警"));
    await(() -> s(diag.get(alice, s(task, "id")), "status").equals("succeeded"));
    var report = store.list("report", alice, s(task, "id")).get(0);
    assertEquals("未确认", s(map(report.get("payload")), "rootCause"));
    assertTrue(s(report, "content").contains("observed error"));
    assertTrue(s(report, "content").contains("## 📊 结论"));
    assertEquals(1, ((List<?>) report.get("evidenceIds")).size());
  }

  @Test
  void successfulJobStatusAndAutoCaseRecoverAcrossRestartWindow() throws Exception {
    jobs.handlers.remove("aiops_diagnosis");
    String task = "restart_diagnostic";
    store.put("diagnostic", alice, "", obj("id", task, "status", "running", "inputPayload", obj()));
    store.put(
        "report",
        alice,
        task,
        obj(
            "id",
            "restart_report",
            "title",
            "真实已保存报告",
            "content",
            "# 告警分析报告\n\n仅列已保存事实",
            "payload",
            obj("rootCause", "未确认"),
            "evidenceIds",
            List.of()));
    var job = jobs.enqueue(alice, "aiops_diagnosis", task, obj(), null);
    store.db.update(
        "UPDATE background_jobs SET status='succeeded',completed_at=? WHERE id=?",
        now(),
        job.get("id"));
    doAnswer(
            inv ->
                Collections.nCopies(
                    ((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model)
        .embeddings(anyList());
    var restored = new DiagnosisService(store, jobs, model, mcp, knowledge);
    await(() -> !restored.cases(alice).isEmpty());
    assertEquals("succeeded", s(restored.get(alice, task), "status"));
    assertTrue(((List<?>) restored.cases(alice).get(0).get("evidenceIds")).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"succeeded", "failed", "cancelled"})
  void terminalDiagnosisReplayRestoresActualTimeAndKeepsHistoryOrder(String status) {
    jobs.stop();
    var old = diag.create(alice, obj("query", "历史任务"));
    var recent = diag.create(alice, obj("query", "较新任务"));
    String oldId = s(old, "id"), recentId = s(recent, "id");
    String oldTime = "2026-10-04T14:15:39.012759Z";
    String recentTime = "2026-10-04T17:35:57.854423Z";
    String created = s(old, "createdAt");
    // Existing case records prevent unrelated case indexing during replay.
    store.put("case", alice, oldId, obj("id", "old-case"));
    store.put("case", alice, recentId, obj("id", "recent-case"));
    store.db.update("UPDATE background_jobs SET status=?,updated_at=?,completed_at=? WHERE resource_id=?",
        status, oldTime, oldTime, oldId);
    store.db.update("UPDATE background_jobs SET status=?,updated_at=?,completed_at=? WHERE resource_id=?",
        status, recentTime, recentTime, recentId);
    old.put("updatedAt", "2026-10-04T17:44:24.999Z");
    store.put("diagnostic", alice, "", old);
    for (int restart = 0; restart < 2; restart++) {
      var restored = new DiagnosisService(store, jobs, model, mcp, knowledge);
      assertEquals(oldTime, s(restored.get(alice, oldId), "updatedAt"));
      assertEquals(oldTime, s(restored.get(alice, oldId), "completedAt"));
      assertEquals(created, s(restored.get(alice, oldId), "createdAt"));
      assertEquals(recentTime, s(restored.get(alice, recentId), "updatedAt"));
      assertEquals(List.of(recentId, oldId), restored.list(alice).stream().map(t -> s(t, "id")).toList());
    }
  }

  @Test
  void slsQueryErrorIsRepairedEvenAfterLastReplanAndFailureRemainsInReport() throws Exception {
    String bad = "service:inventory-service | SELECT date_format(__time__, '%H:%i') AS minute, count(*) AS cnt GROUP BY minute";
    String fixed = "service:inventory-service | SELECT date_format(from_unixtime(__time__), '%H:%i') AS minute, count(*) AS cnt GROUP BY minute";
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs", "inputSchema", obj())));
    when(mcp.call(anyMap(), anyMap())).thenAnswer(inv -> {
      String query = s(inv.getArgument(1), "query");
      if (query.equals(bad)) throw new IllegalStateException("QueryError: Unexpected parameters (bigint, varchar) for function date_format");
      return encode(obj("content", List.of(obj("text", query.equals(fixed)
          ? "[\"{\\\"minute\\\":\\\"22:14\\\",\\\"cnt\\\":3}\"]"
          : "[{\"message\":\"observed inventory lock error\"}]"))));
    });
    AtomicInteger plans = new AtomicInteger();
    doAnswer(inv -> {
      String prompt = inv.getArgument(0);
      if (prompt.contains("修正器")) return encode(obj("arguments", obj("query", fixed), "purpose", "date_format需要timestamp类型"));
      if (prompt.contains("规划器")) return encode(obj("steps", List.of(obj("tool", "sls_query_logs", "arguments", obj("query", plans.incrementAndGet() == 3 ? bad : "service:inventory-service | SELECT *")))));
      return encode(obj("title", "真实日志诊断", "content", "# 告警分析报告\n已取得真实日志", "rootCause", "未确认"));
    }).when(model).answer(anyString(), anyString());
    doAnswer(inv -> Collections.nCopies(((List<?>) inv.getArgument(0)).size(), Collections.nCopies(1024, 0.1f)))
        .when(model).embeddings(anyList());
    var task = diag.create(alice, obj("query", "检查库存异常及时间分布"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("succeeded"));
    verify(mcp).call(anyMap(), argThat(args -> s(args, "query").equals(fixed)));
    var chain = diag.chain(alice, id);
    assertEquals(4, maps(chain.get("toolCalls")).stream().filter(a -> s(a, "toolName").equals("sls_query_logs")).count());
    assertEquals(3, maps(chain.get("reportEvidenceLinks")).size());
    var report = maps(chain.get("reports")).get(0);
    assertTrue(s(report, "content").contains("查询失败与分析限制"));
    assertTrue(s(report, "content").contains("QueryError"));
    assertEquals(1, ((List<?>) map(report.get("payload")).get("failedToolCallIds")).size());
  }

  @Test
  void slsQueryRepairCannotBroadenOriginalSearchFilter() throws Exception {
    String bad = "service:inventory-service | SELECT date_format(__time__, '%H:%i')";
    when(mcp.tools(alice)).thenReturn(Map.of("sls_query_logs", obj("name", "sls_query_logs", "inputSchema", obj())));
    when(mcp.call(anyMap(), anyMap())).thenThrow(new IllegalStateException("QueryError: date_format requires timestamp"));
    doAnswer(inv -> {
      String prompt = inv.getArgument(0);
      if (prompt.contains("修正器")) return encode(obj("arguments", obj("query", "* | SELECT count(*)")));
      return encode(obj("steps", List.of(obj("tool", "sls_query_logs", "arguments", obj("query", bad)))));
    }).when(model).answer(anyString(), anyString());
    var task = diag.create(alice, obj("query", "验证库存查询修正"));
    String id = s(task, "id");
    await(() -> s(diag.get(alice, id), "status").equals("failed"));
    verify(mcp, never()).call(anyMap(), argThat(args -> s(args, "query").startsWith("*")));
    var chain = diag.chain(alice, id);
    assertTrue(maps(chain.get("steps")).stream().anyMatch(step ->
        s(step, "status").equals("failed") && s(map(step.get("payload")), "error").contains("原始检索条件")));
    assertEquals(0, maps(chain.get("evidence")).size());
    assertEquals(1, maps(chain.get("reports")).size());
  }

  @Test
  void mcpToolSpecificTimeoutDoesNotChangeConnectionAndQueryErrorsAreNotBlindlyRetried() {
    config.data.put("mcp", obj("toolTimeoutSeconds", obj("sls_query_logs", 60)));
    var service = spy(new McpService(store, config));
    var client = mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
    doReturn(client).when(service).connect(anyMap());
    var connection = obj("name", "SLS", "timeoutSeconds", 15, "retries", 2);
    var textTool = obj("name", "sls_query_logs", "connection", connection);
    when(client.callTool(anyString(), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
        io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder().addTextContent("generated query").isError(false).build()));
    service.call(textTool, obj());
    verify(service).connect(argThat(c -> n(c.get("timeoutSeconds")) == 60));
    assertEquals(15, n(connection.get("timeoutSeconds")));
    assertEquals(15, service.toolTimeoutSeconds(connection, "sls_list_logstores"));
    assertEquals(120, service.toolTimeoutSeconds(obj("timeoutSeconds", 120), "sls_query_logs"));
    clearInvocations(service, client);
    when(client.callTool(anyString(), anyMap())).thenReturn(reactor.core.publisher.Mono.just(
        io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder().addTextContent(encode(obj("message", "QueryError: invalid date_format", "stack", "INTERNAL_STACK_MARKER"))).isError(true).build()));
    var error = assertThrows(McpService.ToolFailure.class,
        () -> service.call(obj("name", "sls_list_logstores", "connection", connection), obj()));
    assertFalse(error.retryable);
    assertTrue(error.getMessage().contains("invalid date_format"));
    assertFalse(error.getMessage().contains("INTERNAL_STACK_MARKER"));
    verify(client, times(1)).callTool(anyString(), anyMap());
    verify(client).close();
  }

  @Test
  void mcpTimeoutMessageShowsEffectiveTimeoutAndRetainsTransientRetry() {
    config.data.put("mcp", obj("toolTimeoutSeconds", obj("sls_query_logs", 60)));
    var service = spy(new McpService(store, config));
    var client = mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
    doReturn(client).when(service).connect(anyMap());
    when(client.callTool(anyString(), anyMap())).thenReturn(reactor.core.publisher.Mono.error(
        new RuntimeException(new java.util.concurrent.TimeoutException("verbose reactor timeout"))));
    var error = assertThrows(McpService.ToolFailure.class,
        () -> service.call(obj("name", "sls_query_logs", "connection", obj("timeoutSeconds", 15, "retries", 1)), obj()));
    assertTrue(error.retryable);
    assertTrue(error.getMessage().contains("60 秒"));
    verify(client, times(2)).callTool(anyString(), anyMap());
    verify(client, times(2)).close();
  }

  @Test
  void mcpDiscoveryRetriesConfiguredTransientFailureAndClosesClients() {
    var service = spy(new McpService(store, config));
    var client = mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
    doReturn(client).when(service).connect(anyMap());
    doReturn(
            reactor.core.publisher.Mono.error(new IllegalStateException("暂时失败")),
            reactor.core.publisher.Mono.just(List.of()))
        .when(client)
        .listTools();
    assertTrue(service.discover(obj("name", "health", "retries", 1)).isEmpty());
    verify(service, times(2)).connect(anyMap());
    verify(client, times(2)).close();
  }
}
