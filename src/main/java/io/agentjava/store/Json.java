package io.agentjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

public final class Json {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  public static Map<String, Object> obj(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public static String encode(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON 无法编码", e);
    }
  }

  public static Map<String, Object> decode(String v) {
    try {
      return MAPPER.readValue(v, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON 必须为对象", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object v) {
    return v instanceof Map<?, ?> ? (Map<String, Object>) v : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> maps(Object v) {
    return v instanceof List<?> ? (List<Map<String, Object>>) v : new ArrayList<>();
  }

  public static String str(Object v) {
    return v == null ? "" : v.toString();
  }

  public static String s(Map<String, Object> m, String k) {
    return str(m.get(k));
  }

  public static int n(Object v) {
    return v instanceof Number num ? num.intValue() : 0;
  }

  public static String now() {
    return Instant.now().toString();
  }

  public static String id(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }
}
