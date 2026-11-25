package io.agentjava.knowledge;

import static io.agentjava.store.Json.*;

import com.google.gson.*;
import io.agentjava.config.ProjectConfig;
import io.milvus.v2.client.*;
import io.milvus.v2.common.*;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class VectorStore {
  private final ProjectConfig config;
  private MilvusClientV2 client;
  private boolean initialized;

  public VectorStore(ProjectConfig config) {
    this.config = config;
  }

  private synchronized MilvusClientV2 client() {
    if (client == null)
      client =
          new MilvusClientV2(
              ConnectConfig.builder()
                  .uri(config.text("vectorStore", "uri"))
                  .connectTimeoutMs(10000)
                  .rpcDeadlineMs(10000)
                  .build());
    return client;
  }

  public void health() {
    client().listCollections();
  }

  public synchronized void initialize() {
    if (initialized) return;
    String name = config.text("vectorStore", "collectionName");
    if (!client().hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
      var schema = client().createSchema();
      for (String field :
          List.of(
              "chunkId",
              "ownerUserId",
              "tenantId",
              "knowledgeBaseId",
              "documentId",
              "content",
              "source",
              "createdAt"))
        schema.addField(
            AddFieldReq.builder()
                .fieldName(field)
                .dataType(DataType.VarChar)
                .maxLength(field.equals("content") ? 65535 : 1024)
                .isPrimaryKey(field.equals("chunkId"))
                .build());
      schema.addField(AddFieldReq.builder().fieldName("metadata").dataType(DataType.JSON).build());
      schema.addField(
          AddFieldReq.builder()
              .fieldName("vector")
              .dataType(DataType.FloatVector)
              .dimension(1024)
              .build());
      var index =
          IndexParam.builder()
              .fieldName("vector")
              .indexType(IndexParam.IndexType.HNSW)
              .metricType(IndexParam.MetricType.COSINE)
              .extraParams(obj("M", 16, "efConstruction", 200))
              .build();
      client()
          .createCollection(
              CreateCollectionReq.builder()
                  .collectionName(name)
                  .collectionSchema(schema)
                  .indexParams(List.of(index))
                  .consistencyLevel(ConsistencyLevel.STRONG)
                  .build());
    }
    client().loadCollection(LoadCollectionReq.builder().collectionName(name).build());
    initialized = true;
  }

  public static String scope(String owner, String kb) {
    if (owner.isBlank() || !kb.equals("kb_" + owner))
      throw new IllegalArgumentException("无效向量 scope");
    return "ownerUserId == "
        + encode(owner)
        + " && tenantId == "
        + encode(owner)
        + " && knowledgeBaseId == "
        + encode(kb);
  }

  public void delete(String owner, String kb, String document) {
    initialize();
    client()
        .delete(
            DeleteReq.builder()
                .collectionName(config.text("vectorStore", "collectionName"))
                .filter(scope(owner, kb) + " && documentId == " + encode(document))
                .build());
  }

  public void insert(List<Map<String, Object>> rows) {
    initialize();
    Gson gson = new Gson();
    List<JsonObject> data = rows.stream().map(r -> gson.toJsonTree(r).getAsJsonObject()).toList();
    client()
        .insert(
            InsertReq.builder()
                .collectionName(config.text("vectorStore", "collectionName"))
                .data(data)
                .build());
  }

  public List<Map<String, Object>> search(String owner, String kb, List<Float> vector) {
    return search(owner, kb, vector, List.of());
  }

  public List<Map<String, Object>> search(
      String owner, String kb, List<Float> vector, List<String> documents) {
    initialize();
    String filter =
        scope(owner, kb) + (documents.isEmpty() ? "" : " && documentId in " + encode(documents));
    var r =
        client()
            .search(
                SearchReq.builder()
                    .collectionName(config.text("vectorStore", "collectionName"))
                    .annsField("vector")
                    .data(List.of(new FloatVec(vector)))
                    .topK(20)
                    .filter(filter)
                    .searchParams(obj("ef", 64))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
    return r.getSearchResults().get(0).stream()
        .map(h -> obj("id", str(h.getId()), "score", h.getScore()))
        .toList();
  }
}
