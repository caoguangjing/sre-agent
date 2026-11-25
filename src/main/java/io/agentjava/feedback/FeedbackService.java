package io.agentjava.feedback;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.store.Store;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
  private final Store store;

  public FeedbackService(Store store) {
    this.store = store;
  }

  public void validate(String owner, String type, String target, String subject) {
    String kind =
        switch (type) {
          case "chat_message", "citation" -> "message";
          case "diagnostic_report" -> "report";
          case "diagnostic_step" -> "step";
          default -> throw new ApiException("VALIDATION_INVALID_ARGUMENT");
        };
    var entity = store.get(kind, owner, target);
    if (kind.equals("message") && !s(entity, "role").equals("assistant"))
      throw new ApiException("AUTH_FORBIDDEN");
    if (type.equals("citation")) {
      if (subject.isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
      if (maps(map(entity.get("metadata")).get("citations")).stream()
          .noneMatch(c -> s(c, "id").equals(subject))) throw new ApiException("AUTH_FORBIDDEN");
    } else if (!subject.isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
  }

  public synchronized Map<String, Object> upsert(String owner, Map<String, Object> req) {
    String type = s(req, "targetType"), target = s(req, "targetId"), subject = s(req, "subjectId");
    if (!Set.of("positive", "negative").contains(s(req, "rating")))
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    for (var limit : obj("reason", 80, "comment", 2000, "correction", 4000).entrySet())
      if (s(req, limit.getKey()).length() > n(limit.getValue()))
        throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    validate(owner, type, target, subject);
    var previous =
        store.list("feedback", owner, target).stream()
            .filter(f -> s(f, "targetType").equals(type) && s(f, "subjectId").equals(subject))
            .findFirst();
    var v = previous.orElseGet(() -> obj("id", id("feedback"), "createdAt", now()));
    v.putAll(
        obj(
            "targetType",
            type,
            "targetId",
            target,
            "subjectId",
            subject.isBlank() ? null : subject,
            "rating",
            req.get("rating"),
            "reason",
            req.get("reason"),
            "comment",
            req.get("comment"),
            "correction",
            req.get("correction"),
            "updatedAt",
            now()));
    return store.put("feedback", owner, target, v);
  }

  public List<Map<String, Object>> list(String owner, String type, String target) {
    if (!target.isBlank() && !type.isBlank() && !type.equals("citation"))
      validate(owner, type, target, "");
    return store.list("feedback", owner, target.isBlank() ? null : target).stream()
        .filter(v -> type.isBlank() || s(v, "targetType").equals(type))
        .toList();
  }
}
