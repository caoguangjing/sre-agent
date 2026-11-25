package io.agentjava.model;

import static io.agentjava.store.Json.*;

import io.agentjava.config.ProjectConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class QwenProvider {
  public final ProjectConfig config;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public QwenProvider(ProjectConfig config) {
    this.config = config;
  }

  public OpenAIChatModel model() {
    return OpenAIChatModel.builder()
        .apiKey(config.text("llm", "apiKey"))
        .baseUrl(config.text("llm", "baseUrl"))
        .modelName(config.text("llm", "chatModel"))
        .stream(true)
        .contextWindowSize(Math.max(1, config.window()))
        .generateOptions(
            GenerateOptions.builder()
                .temperature(
                    ((Number) config.section("llm").getOrDefault("temperature", 0.2)).doubleValue())
                .additionalBodyParam("enable_thinking", false)
                .build())
        .build();
  }

  public String answer(String system, String text) {
    try (var agent =
        ReActAgent.builder()
            .name("qwen")
            .sysPrompt(system)
            .model(model())
            .maxRetries(config.number("llm", "maxRetries", 2))
            .build()) {
      return agent
          .call(new UserMessage(text))
          .block(Duration.ofSeconds(config.number("llm", "timeoutSeconds", 120)))
          .getTextContent();
    }
  }

  public Map<String, Object> request(String url, Map<String, Object> body) {
    int retries = config.number("llm", "maxRetries", 2);
    for (int i = 0; ; i++) {
      try {
        var req =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.number("llm", "timeoutSeconds", 120)))
                .header("Authorization", "Bearer " + config.text("llm", "apiKey"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(body)))
                .build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
          if (r.statusCode() < 500 && r.statusCode() != 429)
            throw new IllegalArgumentException("Qwen HTTP " + r.statusCode());
          throw new IllegalStateException("Qwen 暂不可用 " + r.statusCode());
        }
        return decode(r.body());
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        if (i >= retries || Thread.currentThread().isInterrupted())
          throw new IllegalStateException("Qwen 请求失败", e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public List<List<Float>> embeddings(List<String> texts) {
    var out = new ArrayList<List<Float>>();
    for (int offset = 0; offset < texts.size(); offset += 10) {
      var batch = texts.subList(offset, Math.min(texts.size(), offset + 10));
      var r =
          request(
              config.text("llm", "baseUrl").replaceAll("/$", "") + "/embeddings",
              obj(
                  "model",
                  config.text("llm", "embeddingModel"),
                  "input",
                  batch,
                  "dimensions",
                  1024,
                  "encoding_format",
                  "float"));
      var rows = new ArrayList<>(maps(r.get("data")));
      rows.sort(Comparator.comparingInt(x -> n(x.get("index"))));
      if (rows.size() != batch.size()) throw new IllegalStateException("Embedding 数量不匹配");
      int expectedIndex = 0;
      for (var row : rows) {
        if (n(row.get("index")) != expectedIndex++)
          throw new IllegalStateException("Embedding index不匹配");
        List<Number> vector = (List<Number>) row.get("embedding");
        if (vector.size() != 1024 || vector.stream().anyMatch(v -> !Float.isFinite(v.floatValue())))
          throw new IllegalStateException("Embedding 维度不匹配");
        out.add(vector.stream().map(Number::floatValue).toList());
      }
    }
    return out;
  }

  public List<Map<String, Object>> rerank(String query, List<String> candidates) {
    if (candidates.isEmpty()) return List.of();
    var r =
        request(
            config.text("llm", "rerankUrl"),
            obj(
                "model",
                config.text("llm", "rerankModel"),
                "input",
                obj("query", query, "documents", candidates),
                "parameters",
                obj("top_n", Math.min(5, candidates.size()), "return_documents", false)));
    var results = maps(map(r.get("output")).get("results"));
    if (results.isEmpty()) throw new IllegalStateException("Rerank 返回空结果");
    return results;
  }
}
