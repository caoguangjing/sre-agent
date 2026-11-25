package io.agentjava.config;

import static io.agentjava.store.Json.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ProjectConfig {
  public final Path root;
  public final Map<String, Object> data;

  public ProjectConfig() {
    this(Path.of(System.getProperty("agentjava.root", ".")));
  }

  public ProjectConfig(Path root) {
    this.root = root.toAbsolutePath().normalize();
    try {
      data =
          merge(
              decode(Files.readString(this.root.resolve("config/project.json"))),
              decode(Files.readString(this.root.resolve("config/user.project.json"))));
    } catch (Exception e) {
      throw new IllegalArgumentException("读取 config/project.json 或 config/user.project.json 失败", e);
    }
  }

  public static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
    var out = new LinkedHashMap<>(a);
    b.forEach(
        (k, v) ->
            out.put(
                k, v instanceof Map && a.get(k) instanceof Map ? merge(map(a.get(k)), map(v)) : v));
    return out;
  }

  public Map<String, Object> section(String name) {
    return map(data.get(name));
  }

  public String text(String group, String key) {
    return s(section(group), key);
  }

  public int number(String group, String key, int fallback) {
    return section(group).get(key) instanceof Number ? n(section(group).get(key)) : fallback;
  }

  public double decimal(String group, String key, double fallback) {
    return section(group).get(key) instanceof Number num ? num.doubleValue() : fallback;
  }

  public Path database() {
    String path =
        text("backend", "memoryDatabaseUrl")
            .replace("jdbc:sqlite:", "")
            .replace("sqlite+aiosqlite:///", "")
            .replace("sqlite:///", "");
    return root.resolve(path).normalize();
  }

  public int window() {
    return n(
        map(map(section("llm").get("modelCapabilities")).get(text("llm", "chatModel")))
            .get("contextWindowTokens"));
  }

  public void validate() {
    for (String name : List.of("backend", "llm", "vectorStore", "mcp"))
      if (!(data.get(name) instanceof Map))
        throw new IllegalArgumentException("配置组 " + name + " 缺失或格式无效");
    if (number("backend", "port", 0) < 1
        || number("backend", "port", 0) > 65535
        || text("backend", "memoryDatabaseUrl").isBlank())
      throw new IllegalArgumentException("backend监听端口/数据库路径无效");
    for (var field :
        List.of(
            new String[] {"llm", "baseUrl"},
            new String[] {"llm", "rerankUrl"},
            new String[] {"vectorStore", "uri"},
            new String[] {"mcp", "slsSseUrl"})) {
      try {
        var uri = java.net.URI.create(text(field[0], field[1]));
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
          throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new IllegalArgumentException(field[0] + "." + field[1] + " URL无效");
      }
    }
    for (String f :
        List.of("apiKey", "baseUrl", "chatModel", "embeddingModel", "rerankModel", "rerankUrl"))
      if (text("llm", f).isBlank()) throw new IllegalArgumentException("llm." + f + " 缺失");
    double minScore = decimal("llm", "rerankMinScore", 0);
    if (minScore < 0 || minScore > 1)
      throw new IllegalArgumentException("llm.rerankMinScore 取值须在0~1之间");
    if (number("llm", "embeddingDimensions", 0) != 1024
        || number("vectorStore", "vectorDimension", 0) != 1024
        || window() < 1) throw new IllegalArgumentException("Embedding 维度须为1024，模型须配置上下文窗口");
  }

  public String safe(Throwable e) {
    if (e == null) return "";
    String t = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    for (String g : List.of("llm", "aiopsDemo", "slsLogUpload"))
      for (String k : List.of("apiKey", "accessKeyId", "accessKeySecret", "password")) {
        String v = text(g, k);
        if (!v.isEmpty()) t = t.replace(v, "[redacted]");
      }
    return t.substring(0, Math.min(600, t.length()));
  }
}
