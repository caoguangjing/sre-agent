package io.agentjava.jobs;

import static io.agentjava.store.Json.*;

import io.agentjava.api.*;
import io.agentjava.store.Store;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class JobRuntime {
  /** A domain handler may supply a safe explanation and mark a failure as non-retryable. */
  public static class ExecutionFailure extends RuntimeException {
    public final boolean retryable;

    public ExecutionFailure(String safeMessage, boolean retryable) {
      super(safeMessage);
      this.retryable = retryable;
    }
  }
  public interface Handler {
    void run(Map<String, Object> job, Protocol.Emitter emit) throws Exception;
  }

  public final Map<String, Handler> handlers = new ConcurrentHashMap<>();
  public final List<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();
  private final Store store;
  private final String worker = id("worker");
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
  private final ExecutorService executor = Executors.newFixedThreadPool(3);
  private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
  private volatile boolean closing;

  public JobRuntime(Store store) {
    this.store = store;
    scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::heartbeat, 2, 2, TimeUnit.SECONDS);
  }

  public synchronized Map<String, Object> enqueue(
      String owner, String kind, String resource, Map<String, Object> payload, String retry) {
    String id = id("job"), t = now();
    store.db.update(
        "INSERT INTO"
            + " background_jobs(id,owner,kind,resource_id,status,payload,available_at,retry_of_job_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,'queued',?,?,?,?,?)",
        id,
        owner,
        kind,
        resource,
        encode(payload),
        t,
        retry,
        t,
        t);
    var j = get(owner, id);
    notifyStatus(j);
    return j;
  }

  public Map<String, Object> get(String owner, String id) {
    var rows =
        store.db.queryForList("SELECT * FROM background_jobs WHERE owner=? AND id=?", owner, id);
    if (rows.isEmpty()) throw new ApiException("AUTH_FORBIDDEN");
    return convert(rows.get(0));
  }

  public Map<String, Object> resource(String owner, String rid) {
    var rows =
        store.db.queryForList(
            "SELECT * FROM background_jobs WHERE owner=? AND resource_id=? ORDER BY created_at DESC"
                + " LIMIT 1",
            owner,
            rid);
    if (rows.isEmpty()) throw new ApiException("AUTH_FORBIDDEN");
    return convert(rows.get(0));
  }

  public List<Map<String, Object>> list(String owner) {
    return store
        .db
        .queryForList("SELECT * FROM background_jobs WHERE owner=? ORDER BY created_at DESC", owner)
        .stream()
        .map(this::convert)
        .toList();
  }

  private Map<String, Object> convert(Map<String, Object> r) {
    var out = new LinkedHashMap<String, Object>();
    r.forEach(
        (k, v) -> {
          String[] p = k.split("_");
          StringBuilder b = new StringBuilder(p[0]);
          for (int i = 1; i < p.length; i++)
            b.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1));
          out.put(b.toString(), v);
        });
    out.put("ownerUserId", r.get("owner"));
    out.put("payload", decode(str(r.get("payload"))));
    out.put(
        "resourceType",
        r.get("kind").equals("document_index") ? "document_index_task" : "diagnostic_task");
    return out;
  }

  public synchronized void append(Map<String, Object> job, Map<String, Object> event) {
    String jid = s(job, "id");
    int seq =
        store.db.queryForObject(
            "SELECT coalesce(max(sequence),0)+1 FROM background_job_events WHERE job_id=?",
            Integer.class,
            jid);
    event.put("id", jid + ":" + seq);
    store.db.update("INSERT INTO background_job_events VALUES(?,?,?)", jid, seq, encode(event));
  }

  public List<Map<String, Object>> events(String owner, String id, int after) {
    get(owner, id);
    return store
        .db
        .queryForList(
            "SELECT data FROM background_job_events WHERE job_id=? AND sequence>? ORDER BY"
                + " sequence",
            id,
            after)
        .stream()
        .map(r -> decode(str(r.get("data"))))
        .toList();
  }

  public synchronized Map<String, Object> cancel(String owner, String id) {
    var j = get(owner, id);
    if (Set.of("succeeded", "failed", "cancelled").contains(s(j, "status")))
      throw new ApiException("BUSINESS_CONFLICT");
    store.db.update(
        "UPDATE background_jobs SET"
            + " cancel_requested_at=?,status='cancelled',updated_at=?,completed_at=? WHERE owner=?"
            + " AND id=?",
        now(),
        now(),
        now(),
        owner,
        id);
    Future<?> f = active.get(id);
    if (f != null) f.cancel(true);
    j = get(owner, id);
    notifyStatus(j);
    append(
        j, Protocol.event(channel(j), "error", obj("error", Protocol.error("BUSINESS_CONFLICT"))));
    return j;
  }

  public Map<String, Object> retry(String owner, String id) {
    var j = get(owner, id);
    if (!Set.of("failed", "cancelled").contains(s(j, "status")))
      throw new ApiException("BUSINESS_CONFLICT");
    return enqueue(owner, s(j, "kind"), s(j, "resourceId"), map(j.get("payload")), id);
  }

  public void replayTerminalStatus(String kind, Consumer<Map<String, Object>> listener) {
    for (var row :
        store.db.queryForList(
            "SELECT * FROM background_jobs WHERE kind=? AND status IN"
                + " ('succeeded','failed','cancelled')",
            kind)) {
      try {
        listener.accept(convert(row));
      } catch (Exception ignored) {
        /* Deleted resources need no replay. */
      }
    }
  }

  private void tick() {
    if (closing) return;
    try {
      for (var expired :
          store.db.queryForList(
              "SELECT * FROM background_jobs WHERE status='running' AND lease_expires_at<?",
              now())) {
        boolean retry = n(expired.get("attempt")) < n(expired.get("max_attempts"));
        int changed =
            store.db.update(
                "UPDATE background_jobs SET"
                    + " status=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=?,completed_at=?,error_message=?"
                    + " WHERE id=? AND status='running' AND lease_expires_at<?",
                retry ? "queued" : "failed",
                now(),
                retry ? null : now(),
                retry ? "工作进程租约过期，等待恢复" : "工作进程租约过期且尝试次数已耗尽",
                expired.get("id"),
                now());
        if (changed == 1) {
          var recovered = get(str(expired.get("owner")), str(expired.get("id")));
          if (!retry)
            append(
                recovered,
                Protocol.event(
                    channel(recovered),
                    "error",
                    obj("error", Protocol.error("SYSTEM_UNAVAILABLE"))));
          notifyStatus(recovered);
        }
      }
      active.entrySet().removeIf(e -> e.getValue().isDone());
      if (active.size() >= 3) return;
      for (var row :
          store.db.queryForList(
              "SELECT * FROM background_jobs WHERE status='queued' AND available_at<=? ORDER BY"
                  + " created_at LIMIT 3",
              now())) {
        if (active.size() >= 3) break;
        String jid = str(row.get("id"));
        if (!handlers.containsKey(str(row.get("kind")))) continue;
        int claimed =
            store.db.update(
                "UPDATE background_jobs SET"
                    + " status='running',attempt=attempt+1,lease_owner=?,lease_expires_at=?,started_at=coalesce(started_at,?),updated_at=?"
                    + " WHERE id=? AND status='queued'",
                worker,
                Instant.now().plusSeconds(15).toString(),
                now(),
                now(),
                jid);
        if (claimed == 1) {
          var task =
              new FutureTask<Void>(
                  () -> {
                    execute(str(row.get("owner")), jid);
                    return null;
                  });
          active.put(jid, task);
          executor.execute(task);
        }
      }
    } catch (Exception ignored) {
      /* next tick retries DB contention */
    }
  }

  private void execute(String owner, String id) {
    var j = get(owner, id);
    Map<String, Object> terminalError = null;
    try {
      notifyStatus(j);
      final var executing = j;
      handlers
          .get(s(j, "kind"))
          .run(
              j,
              e -> {
                check(owner, id);
                append(executing, e);
              });
      check(owner, id);
      store.db.update(
          "UPDATE background_jobs SET"
              + " status='succeeded',updated_at=?,completed_at=?,lease_owner=NULL,lease_expires_at=NULL"
              + " WHERE id=? AND status='running' AND lease_owner=?",
          now(),
          now(),
          id,
          worker);
    } catch (Exception e) {
      j = get(owner, id);
      if (!s(j, "status").equals("cancelled") && !closing) {
        boolean retry = n(j.get("attempt")) < n(j.get("maxAttempts"))
            && (!(e instanceof ExecutionFailure failure) || failure.retryable);
        String message = e instanceof ExecutionFailure ? e.getMessage() : "外部执行失败";
        store.db.update(
            "UPDATE background_jobs SET"
                + " status=?,available_at=?,error_message=?,updated_at=?,completed_at=?,lease_owner=NULL,lease_expires_at=NULL"
                + " WHERE id=? AND status='running' AND lease_owner=?",
            retry ? "queued" : "failed",
            Instant.now()
                .plusSeconds(Math.min(30, 1L << Math.min(5, n(j.get("attempt")))))
                .toString(),
            message,
            now(),
            retry ? null : now(),
            id,
            worker);
        if (!retry) {
          var error = Protocol.error("SYSTEM_UNAVAILABLE");
          error.put("message", message);
          terminalError = Protocol.event(channel(j), "error", obj("error", error));
        }
      }
    } finally {
      active.remove(id);
      notifyStatus(get(owner, id));
      if (terminalError != null) append(get(owner, id), terminalError);
    }
  }

  public void check(String owner, String id) {
    var j = get(owner, id);
    if (closing
        || Thread.currentThread().isInterrupted()
        || !s(j, "status").equals("running")
        || !s(j, "leaseOwner").equals(worker)) throw new CancellationException();
    if (Instant.parse(s(j, "startedAt"))
        .plusSeconds(n(j.get("timeoutSeconds")))
        .isBefore(Instant.now())) throw new IllegalStateException("任务超时");
  }

  private void heartbeat() {
    try {
      for (String id : active.keySet())
        store.db.update(
            "UPDATE background_jobs SET lease_expires_at=? WHERE id=? AND status='running' AND"
                + " lease_owner=?",
            Instant.now().plusSeconds(15).toString(),
            id,
            worker);
    } catch (Exception ignored) {
    }
  }

  private void notifyStatus(Map<String, Object> j) {
    org.slf4j.LoggerFactory.getLogger(JobRuntime.class)
        .atInfo()
        .addKeyValue("event", "job.status")
        .addKeyValue("jobId", j.get("id"))
        .addKeyValue("resourceId", j.get("resourceId"))
        .addKeyValue("kind", j.get("kind"))
        .addKeyValue("status", j.get("status"))
        .addKeyValue("attempt", j.get("attempt"))
        .log("job.status");
    for (var l : listeners) {
      try {
        l.accept(j);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(JobRuntime.class)
            .warn("job.status listener failed jobId={} kind={}", j.get("id"), j.get("kind"));
      }
    }
  }

  private String channel(Map<String, Object> j) {
    return s(j, "kind").equals("aiops_diagnosis") ? "aiops" : "chat";
  }

  @PreDestroy
  public void stop() {
    closing = true;
    scheduler.shutdownNow();
    executor.shutdownNow();
    store.db.update(
        "UPDATE background_jobs SET status='queued',lease_owner=NULL,lease_expires_at=NULL WHERE"
            + " status='running' AND lease_owner=?",
        worker);
  }
}
