package io.agentjava.agent;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import io.agentjava.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class AssetService {
  private final Store store;

  public AssetService(Store store) {
    this.store = store;
  }

  public synchronized Map<String, Object> configuration(String owner) {
    var prompts = store.list("prompt", owner, "");
    if (prompts.isEmpty()) {
      savePrompt(owner, "", obj("label", "通用助手", "content", "用中文清晰可靠地回答。依据真实工具结果，不编造引用或诊断。"));
      prompts = store.list("prompt", owner, "");
    }
    var skills = store.list("skill", owner, "");
    Map<String, Object> selection;
    try {
      selection = store.get("selection", owner, "selection_" + owner);
    } catch (ApiException e) {
      selection =
          obj(
              "id",
              "selection_" + owner,
              "systemPromptId",
              prompts.get(0).get("id"),
              "skillIds",
              new ArrayList<>(),
              "updatedAt",
              now());
    }
    var promptIds = prompts.stream().map(p -> s(p, "id")).toList();
    if (!promptIds.contains(s(selection, "systemPromptId")))
      selection.put("systemPromptId", promptIds.get(0));
    var skillIds = skills.stream().map(sk -> s(sk, "id")).toList();
    List<?> selected = selection.get("skillIds") instanceof List<?> l ? l : List.of();
    selection.put(
        "skillIds", selected.stream().filter(x -> skillIds.contains(str(x))).distinct().toList());
    store.put("selection", owner, "", selection);
    return obj(
        "prompts",
        prompts,
        "skills",
        skills.stream()
            .map(
                sk -> {
                  var cp = new LinkedHashMap<>(sk);
                  cp.remove("content");
                  return cp;
                })
            .toList(),
        "selection",
        selection);
  }

  public Map<String, Object> select(String owner, Map<String, Object> req) {
    store.get("prompt", owner, s(req, "systemPromptId"));
    if (!(req.get("skillIds") instanceof List<?> ids))
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    for (Object id : ids) store.get("skill", owner, str(id));
    store.put(
        "selection",
        owner,
        "",
        obj(
            "id",
            "selection_" + owner,
            "systemPromptId",
            req.get("systemPromptId"),
            "skillIds",
            ids,
            "updatedAt",
            now()));
    return configuration(owner);
  }

  public Map<String, Object> savePrompt(String owner, String pid, Map<String, Object> req) {
    if (s(req, "label").isBlank()
        || s(req, "label").length() > 160
        || s(req, "content").isBlank()
        || s(req, "content").length() > 12000)
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    var p =
        pid.isEmpty()
            ? obj(
                "id",
                id("prompt"),
                "createdAt",
                now(),
                "isDefault",
                store.list("prompt", owner, "").isEmpty())
            : store.get("prompt", owner, pid);
    p.putAll(
        obj(
            "label",
            s(req, "label").trim(),
            "content",
            s(req, "content").trim(),
            "updatedAt",
            now()));
    return store.put("prompt", owner, "", p);
  }

  public Map<String, Object> skill(String owner, String filename, String text) {
    if (!"SKILL.md".equals(filename)
        || text.getBytes(StandardCharsets.UTF_8).length > 65536
        || !text.startsWith("---\n")) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    int end = text.indexOf("\n---", 4);
    if (end < 0) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    Map<String, Object> front;
    try {
      front = map(new Yaml().load(text.substring(4, end)));
    } catch (Exception e) {
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    }
    String name = s(front, "name"), desc = s(front, "description");
    if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
        || name.length() > 64
        || desc.isBlank()
        || desc.length() > 1024
        || text.substring(end + 4).isBlank()) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    if (store.list("skill", owner, "").stream().anyMatch(sk -> s(sk, "name").equals(name)))
      throw new ApiException("BUSINESS_CONFLICT");
    var sk =
        obj(
            "id",
            id("skill"),
            "filename",
            "SKILL.md",
            "name",
            name,
            "description",
            desc,
            "label",
            name,
            "contentPreview",
            desc,
            "sizeBytes",
            text.getBytes(StandardCharsets.UTF_8).length,
            "content",
            text,
            "createdAt",
            now(),
            "updatedAt",
            now());
    store.put("skill", owner, "", sk);
    var out = new LinkedHashMap<>(sk);
    out.remove("content");
    return out;
  }

  public Map<String, Object> skillContent(String owner, String skillId) {
    return store.get("skill", owner, skillId);
  }

  public String system(String owner) {
    var selection = map(configuration(owner).get("selection"));
    String prompt = s(store.get("prompt", owner, s(selection, "systemPromptId")), "content");
    List<?> ids = (List<?>) selection.get("skillIds");
    for (Object id : ids) {
      var sk = store.get("skill", owner, str(id));
      prompt +=
          "\n可按需使用load_skill: id="
              + id
              + ", name="
              + s(sk, "name")
              + ", description="
              + s(sk, "description");
    }
    return prompt;
  }

  public String load(String owner, String skillId) {
    var selection = map(configuration(owner).get("selection"));
    List<?> ids = (List<?>) selection.get("skillIds");
    for (Object id : ids) {
      var sk = store.get("skill", owner, str(id));
      if (str(id).equals(skillId) || s(sk, "name").equals(skillId)) return s(sk, "content");
    }
    throw new ApiException("AUTH_FORBIDDEN");
  }
}
