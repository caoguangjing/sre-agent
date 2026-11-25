package io.agentjava.knowledge;

import static io.agentjava.store.Json.*;

import io.agentjava.api.*;
import io.agentjava.config.ProjectConfig;
import io.agentjava.jobs.JobRuntime;
import io.agentjava.model.QwenProvider;
import io.agentjava.store.Store;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
  public final Store store;
  public final VectorStore vectors;
  public final QwenProvider model;
  private final JobRuntime jobs;
  private final ProjectConfig config;

  public KnowledgeService(
      Store store, VectorStore vectors, QwenProvider model, JobRuntime jobs, ProjectConfig config) {
    this.store = store;
    this.vectors = vectors;
    this.model = model;
    this.jobs = jobs;
    this.config = config;
    jobs.handlers.put("document_index", this::index);
    jobs.listeners.add(this::status);
    jobs.replayTerminalStatus("document_index", this::status);
  }

  public void scope(String owner, String kb) {
    if (owner.isBlank() || !kb.equals("kb_" + owner)) throw new ApiException("AUTH_FORBIDDEN");
  }

  public List<Map<String, Object>> list(String owner, String kb) {
    scope(owner, kb);
    return store.list("document", owner, kb).stream()
        .filter(d -> s(d, "status").equals("ready"))
        .sorted(Comparator.comparing((Map<String, Object> d) -> java.time.Instant.parse(s(d, "uploadedAt")))
            .reversed().thenComparing(d -> s(d, "id")))
        .map(this::publicDocument)
        .toList();
  }

  public Map<String, Object> get(String owner, String kb, String id) {
    scope(owner, kb);
    var d = store.get("document", owner, id);
    if (!s(d, "knowledgeBaseId").equals(kb) || !s(d, "status").equals("ready"))
      throw new ApiException("AUTH_FORBIDDEN");
    return d;
  }

  public Map<String, Object> publicDocument(Map<String, Object> d) {
    var out = new LinkedHashMap<>(d);
    out.remove("content");
    out.remove("metadata");
    return out;
  }

  public synchronized Map<String, Object> upload(
      String owner,
      String kb,
      String name,
      String mime,
      byte[] bytes,
      Map<String, Object> chunking,
      boolean overwrite,
      Map<String, Object> metadata) {
    scope(owner, kb);
    DocumentSplitter.validate(chunking);
    if (bytes.length == 0
        || bytes.length > 10 * 1024 * 1024
        || name == null
        || name.contains("/")
        || name.contains("\\")
        || !Set.of("text/markdown", "text/plain", "application/pdf", "application/octet-stream", "")
            .contains(mime)) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    String text;
    try {
      if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
        if (!new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.US_ASCII)
            .equals("%PDF-")) throw new Exception();
        try (var pdf = Loader.loadPDF(bytes)) {
          text = new PDFTextStripper().getText(pdf);
        }
      } else if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
        text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
      } else throw new Exception();
    } catch (Exception e) {
      throw new ApiException("VALIDATION_INVALID_ARGUMENT", "仅接受有效UTF-8 Markdown或PDF");
    }
    if (text.isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    String hash;
    try {
      hash =
          "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    String duplicate = null;
    for (var old : store.list("document", owner, kb)) {
      if (s(old, "status").equals("ready")
          && (s(old, "filename").equals(name) || s(old, "contentHash").equals(hash))) {
        duplicate = s(old, "id");
        if (!overwrite) throw new ApiException("BUSINESS_CONFLICT");
        delete(owner, kb, duplicate);
      }
    }
    var d =
        obj(
            "id",
            id("doc"),
            "knowledgeBaseId",
            kb,
            "filename",
            name,
            "sizeBytes",
            bytes.length,
            "mimeType",
            mime,
            "contentHash",
            hash,
            "status",
            "ready",
            "indexStatus",
            "pending",
            "chunking",
            chunking,
            "content",
            text,
            "metadata",
            metadata,
            "source",
            name,
            "uploadedAt",
            now(),
            "updatedAt",
            now());
    store.put("document", owner, kb, d);
    return obj(
        "document", publicDocument(d), "duplicateOfDocumentId", duplicate, "overwrite", overwrite);
  }

  public synchronized void delete(String owner, String kb, String id) {
    var d = get(owner, kb, id);
    for (var task : store.list("index", owner, id)) {
      var job = jobs.resource(owner, s(task, "id"));
      if (Set.of("queued", "running").contains(s(job, "status"))) {
        jobs.cancel(owner, s(job, "id"));
        if (s(job, "status").equals("running")) throw new ApiException("BUSINESS_CONFLICT");
      }
    }
    vectors.delete(owner, kb, id);
    store.db.update(
        "DELETE FROM knowledge_chunks WHERE owner=? AND kb=? AND document_id=?", owner, kb, id);
    d.put("status", "deleted");
    store.put("document", owner, kb, d);
  }

  public Map<String, Object> preview(String owner, String kb, String id) {
    var d = get(owner, kb, id);
    var chunks = DocumentSplitter.split(s(d, "content"), map(d.get("chunking")));
    var items = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < Math.min(12, chunks.size()); i++)
      items.add(
          obj(
              "index",
              i + 1,
              "characterCount",
              chunks.get(i).codePointCount(0, chunks.get(i).length()),
              "excerpt",
              DocumentSplitter.excerpt(chunks.get(i), 400)));
    return obj(
        "configuration",
        d.get("chunking"),
        "totalChunks",
        chunks.size(),
        "truncated",
        chunks.size() > 12,
        "items",
        items);
  }

  public synchronized Map<String, Object> createIndex(
      String owner, String kb, String document, String retry) {
    get(owner, kb, document);
    for (var t : store.list("index", owner, document))
      if (Set.of("pending", "running").contains(s(t, "status")))
        throw new ApiException("BUSINESS_CONFLICT");
    var t =
        obj(
            "id",
            id("index"),
            "knowledgeBaseId",
            kb,
            "documentId",
            document,
            "status",
            "pending",
            "failureReason",
            null,
            "retryOfTaskId",
            retry,
            "createdAt",
            now(),
            "updatedAt",
            now(),
            "startedAt",
            null,
            "completedAt",
            null);
    store.put("index", owner, document, t);
    jobs.enqueue(
        owner,
        "document_index",
        s(t, "id"),
        obj("documentId", document, "knowledgeBaseId", kb),
        null);
    return t;
  }

  private void index(Map<String, Object> job, Protocol.Emitter emit) {
    String owner = s(job, "ownerUserId"),
        kb = s(map(job.get("payload")), "knowledgeBaseId"),
        id = s(map(job.get("payload")), "documentId");
    var d = get(owner, kb, id);
    var texts = DocumentSplitter.split(s(d, "content"), map(d.get("chunking")));
    var embeddings = model.embeddings(texts);
    jobs.check(owner, s(job, "id"));
    vectors.delete(owner, kb, id);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < texts.size(); i++) {
      var meta = new LinkedHashMap<>(map(d.get("metadata")));
      meta.putAll(
          obj(
              "chunkIndex",
              i + 1,
              "chunking",
              d.get("chunking"),
              "ownerUserId",
              owner,
              "tenantId",
              owner,
              "knowledgeBaseId",
              kb,
              "documentId",
              id));
      rows.add(
          obj(
              "chunkId",
              id + "_" + i,
              "ownerUserId",
              owner,
              "tenantId",
              owner,
              "knowledgeBaseId",
              kb,
              "documentId",
              id,
              "content",
              texts.get(i),
              "source",
              s(d, "filename"),
              "createdAt",
              now(),
              "metadata",
              meta,
              "vector",
              embeddings.get(i)));
    }
    vectors.insert(rows);
    jobs.check(owner, s(job, "id"));
    synchronized (store) {
      store.db.update("DELETE FROM knowledge_chunks WHERE owner=? AND document_id=?", owner, id);
      for (var r : rows)
        store.db.update(
            "INSERT INTO knowledge_chunks VALUES(?,?,?,?,?,?,?)",
            r.get("chunkId"),
            owner,
            kb,
            id,
            r.get("content"),
            r.get("source"),
            encode(r.get("metadata")));
    }
  }

  private void status(Map<String, Object> job) {
    if (!s(job, "kind").equals("document_index")) return;
    String owner = s(job, "ownerUserId");
    var t = store.get("index", owner, s(job, "resourceId"));
    String status = s(job, "status");
    t.putAll(
        obj(
            "status",
            status.equals("queued") ? "pending" : status,
            "updatedAt",
            now(),
            "startedAt",
            job.get("startedAt"),
            "completedAt",
            job.get("completedAt"),
            "failureReason",
            Set.of("failed", "cancelled").contains(status) ? "索引失败，请检查Embedding与Milvus" : null));
    store.put("index", owner, s(t, "documentId"), t);
    var d = store.get("document", owner, s(t, "documentId"));
    if (!s(d, "status").equals("ready")) return;
    d.put(
        "indexStatus",
        switch (status) {
          case "running" -> "indexing";
          case "succeeded" -> "indexed";
          case "failed", "cancelled" -> "failed";
          default -> "pending";
        });
    d.put("updatedAt", now());
    store.put("document", owner, s(t, "knowledgeBaseId"), d);
  }

  public List<Map<String, Object>> retrieve(String owner, String query) {
    return retrieve(owner, obj("query", query));
  }

  public List<Map<String, Object>> retrieve(String owner, Map<String, Object> input) {
    String query = s(input, "query").trim();
    int topK = input.containsKey("topK") ? n(input.get("topK")) : 5;
    if (query.isBlank() || query.length() > 10000 || topK < 1 || topK > 5)
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    String kb = "kb_" + owner;
    var filters = map(input.get("filters"));
    var kbIds =
        filters.get("knowledgeBaseIds") instanceof List<?> ids
            ? ids.stream().map(Object::toString).toList()
            : List.<String>of();
    for (String id : kbIds) scope(owner, id);
    var docIds =
        filters.get("documentIds") instanceof List<?> ids
            ? ids.stream().map(Object::toString).toList()
            : List.<String>of();
    for (String id : docIds) get(owner, kb, id);
    var metadata = map(filters.get("metadata"));
    var chunks =
        store
            .db
            .queryForList("SELECT * FROM knowledge_chunks WHERE owner=? AND kb=?", owner, kb)
            .stream()
            .filter(c -> docIds.isEmpty() || docIds.contains(s(c, "document_id")))
            .filter(
                c ->
                    metadata.entrySet().stream()
                        .allMatch(
                            e ->
                                Objects.equals(
                                    decode(s(c, "metadata")).get(e.getKey()), e.getValue())))
            .toList();
    if (chunks.isEmpty()) return List.of();
    var vectorFuture =
        CompletableFuture.supplyAsync(
            () ->
                docIds.isEmpty()
                    ? vectors.search(owner, kb, model.embeddings(List.of(query)).get(0))
                    : vectors.search(owner, kb, model.embeddings(List.of(query)).get(0), docIds));
    var bmFuture = CompletableFuture.supplyAsync(() -> bm25(query, chunks));
    var vector = vectorFuture.join();
    var bm = bmFuture.join();
    Map<String, Map<String, Object>> fused = new LinkedHashMap<>();
    for (int i = 0; i < vector.size(); i++) {
      var h = vector.get(i);
      fused.put(
          s(h, "id"),
          obj(
              "chunkId",
              s(h, "id"),
              "vectorRank",
              i + 1,
              "vectorScore",
              h.get("score"),
              "rrfScore",
              1.0 / (60 + i + 1)));
    }
    for (int i = 0; i < Math.min(20, bm.size()); i++) {
      var h = bm.get(i);
      var f = fused.computeIfAbsent(s(h, "id"), x -> obj("chunkId", x, "rrfScore", 0.0));
      f.put("bm25Rank", i + 1);
      f.put("bm25Score", h.get("score"));
      f.put("rrfScore", ((Number) f.get("rrfScore")).doubleValue() + 1.0 / (60 + i + 1));
    }
    var candidates = new ArrayList<Map<String, Object>>();
    for (var ch : chunks) {
      var f = fused.get(s(ch, "id"));
      if (f == null) continue;
      var d = get(owner, kb, s(ch, "document_id"));
      if (!s(d, "indexStatus").equals("indexed")) continue;
      for (String key : List.of("vectorRank", "vectorScore", "bm25Rank", "bm25Score"))
        f.putIfAbsent(key, null);
      f.putAll(
          obj(
              "ownerUserId",
              owner,
              "tenantId",
              owner,
              "id",
              s(ch, "id"),
              "documentId",
              s(ch, "document_id"),
              "knowledgeBaseId",
              kb,
              "title",
              s(ch, "source"),
              "source",
              s(ch, "source"),
              "sourceType",
              "knowledge-base",
              "content",
              s(ch, "content"),
              "excerpt",
              DocumentSplitter.excerpt(s(ch, "content"), 600),
              "metadata",
              decode(s(ch, "metadata")),
              "knowledgeType",
              map(d.get("metadata")).getOrDefault("knowledgeType", "document")));
      candidates.add(f);
    }
    candidates.sort(
        Comparator.<Map<String, Object>>comparingDouble(
                x -> -((Number) x.get("rrfScore")).doubleValue())
            .thenComparing(x -> s(x, "chunkId")));
    candidates = new ArrayList<>(candidates.subList(0, Math.min(20, candidates.size())));
    if (candidates.isEmpty()) return List.of();
    var ranks = model.rerank(query, candidates.stream().map(x -> s(x, "content")).toList());
    double minScore = config.decimal("llm", "rerankMinScore", 0);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var rank : ranks) {
      int index = n(rank.get("index"));
      if (index < 0 || index >= candidates.size())
        throw new IllegalStateException("Rerank index无效");
      if (minScore > 0 && ((Number) rank.get("relevance_score")).doubleValue() < minScore)
        continue;
      var c = candidates.get(index);
      c.putAll(
          obj(
              "rerankRank",
              out.size() + 1,
              "rerankScore",
              rank.get("relevance_score"),
              "score",
              rank.get("relevance_score")));
      out.add(c);
      if (out.size() == topK) break;
    }
    return out;
  }

  public static List<String> tokens(String text) {
    List<String> out = new ArrayList<>();
    var m =
        java.util.regex.Pattern.compile("[a-zA-Z0-9_]+|[\\p{IsHan}]")
            .matcher(text.toLowerCase(Locale.ROOT));
    String previousHan = null;
    while (m.find()) {
      String token = m.group();
      out.add(token);
      boolean han =
          token.codePointCount(0, token.length()) == 1
              && Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN;
      if (han && previousHan != null) out.add(previousHan + token);
      previousHan = han ? token : null;
    }
    return out;
  }

  public static List<Map<String, Object>> bm25(String query, List<Map<String, Object>> chunks) {
    var docs = chunks.stream().map(c -> tokens(s(c, "content"))).toList();
    double avg = docs.stream().mapToInt(List::size).average().orElse(1);
    Set<String> terms = new HashSet<>(tokens(query));
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < docs.size(); i++) {
      var doc = docs.get(i);
      double score = 0;
      for (String term : terms) {
        long df = docs.stream().filter(d -> d.contains(term)).count();
        long tf = doc.stream().filter(term::equals).count();
        if (tf == 0) continue;
        double idf = Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5));
        double ctd = tf / (1 - 0.75 + 0.75 * doc.size() / Math.max(1, avg));
        score += idf * 2.5 * (ctd + 0.5) / (1.5 + ctd + 0.5);
      }
      if (score > 0) out.add(obj("id", s(chunks.get(i), "id"), "score", score));
    }
    out.sort(
        Comparator.<Map<String, Object>>comparingDouble(
                x -> -((Number) x.get("score")).doubleValue())
            .thenComparing(x -> s(x, "id")));
    return out;
  }
}
