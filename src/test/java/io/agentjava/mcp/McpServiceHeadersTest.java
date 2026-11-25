package io.agentjava.mcp;

import static io.agentjava.store.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agentjava.api.ApiException;
import io.agentjava.auth.AuthService;
import io.agentjava.config.ProjectConfig;
import io.agentjava.store.Store;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class McpServiceHeadersTest {
  @TempDir Path root;
  Store store;
  McpService mcp;
  String owner = "user_1";

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("config"));
    Files.writeString(
        root.resolve("config/project.json"),
        encode(obj("backend", obj("memoryDatabaseUrl", "jdbc:sqlite:./test.db"))));
    Files.writeString(root.resolve("config/user.project.json"), "{}");
    var config = new ProjectConfig(root);
    store = new Store(config);
    var auth = new AuthService(store);
    var user =
        auth.register(
            obj("email", "alice@example.test", "displayName", "Alice", "password", "TestPass123!"));
    owner = s(map(user.get("user")), "id");
    mcp = new McpService(store, config);
  }

  private Map<String, Object> request(Map<String, Object> headers) {
    var req =
        obj(
            "name",
            "sunfire",
            "transport",
            "streamable_http",
            "url",
            "https://mcp.example.com/mcp",
            "enabled",
            true,
            "timeoutSeconds",
            15,
            "retries",
            1);
    if (headers != null) req.put("headers", headers);
    return req;
  }

  @Test
  void saveStoresRealHeaderAndListReturnsMasked() {
    var saved = mcp.save(owner, "", request(obj("Authorization", "Bearer mcpa_token1234567890")));
    assertEquals("Bear***7890", s(map(saved.get("headers")), "Authorization"));
    var raw =
        store.get("mcp", owner, s(saved, "id"));
    assertEquals("Bearer mcpa_token1234567890", s(map(raw.get("headers")), "Authorization"));
    var item = mcp.list(owner).get(0);
    assertEquals("Bear***7890", s(map(item.get("headers")), "Authorization"));
  }

  @Test
  void rejectsDisallowedKeysAndInvalidValues() {
    assertThrows(
        ApiException.class,
        () -> mcp.save(owner, "", request(obj("Host", "evil.example.com"))));
    assertThrows(
        ApiException.class,
        () -> mcp.save(owner, "", request(obj("Authorization", "  "))));
    assertThrows(
        ApiException.class,
        () -> mcp.save(owner, "", request(obj("Authorization", 123))));
    var ok = assertDoesNotThrow(() -> mcp.save(owner, "", request(obj("X-Request-Id", "abc"))));
    assertEquals("[redacted]", s(map(ok.get("headers")), "X-Request-Id"));
  }

  @Test
  void maskedRoundTripPreservesRealValue() {
    var saved = mcp.save(owner, "", request(obj("Authorization", "Bearer mcpa_token1234567890")));
    String id = s(saved, "id");
    var masked = s(map(saved.get("headers")), "Authorization");
    var updated =
        mcp.save(
            owner,
            id,
            request(obj("Authorization", masked)));
    assertEquals(masked, s(map(updated.get("headers")), "Authorization"));
    var raw = store.get("mcp", owner, id);
    assertEquals("Bearer mcpa_token1234567890", s(map(raw.get("headers")), "Authorization"));
  }

  @Test
  void updateWithoutHeadersKeepsExisting() {
    var saved = mcp.save(owner, "", request(obj("Authorization", "Bearer mcpa_token1234567890")));
    String id = s(saved, "id");
    mcp.save(owner, id, request(null));
    var raw = store.get("mcp", owner, id);
    assertEquals("Bearer mcpa_token1234567890", s(map(raw.get("headers")), "Authorization"));
  }

  @Test
  void nullHeadersTreatedAsAbsent() {
    var saved = mcp.save(owner, "", request(null));
    assertTrue(map(saved.get("headers")).isEmpty());
  }
}
