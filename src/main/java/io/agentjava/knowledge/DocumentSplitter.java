package io.agentjava.knowledge;

import static io.agentjava.store.Json.*;

import io.agentjava.api.ApiException;
import java.util.*;

public final class DocumentSplitter {
  public static Map<String, Object> validate(Map<String, Object> c) {
    String type = s(c, "strategy");
    if (!Set.of("fixed-character", "paragraph", "markdown-heading").contains(type))
      throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    if (type.equals("fixed-character")) {
      int size = n(c.get("maxCharacters")), overlap = n(c.get("overlapCharacters"));
      if (size < 100 || size > 10000 || overlap < 0 || overlap >= size || c.size() != 3)
        throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    } else if (c.size() != 1) throw new ApiException("VALIDATION_INVALID_ARGUMENT");
    return c;
  }

  public static List<String> split(String text, Map<String, Object> c) {
    validate(c);
    text = text.replace("\r\n", "\n").trim();
    if (text.isBlank()) return List.of();
    String strategy = s(c, "strategy");
    if (strategy.equals("fixed-character"))
      return fixed(text, n(c.get("maxCharacters")), n(c.get("overlapCharacters")));
    var units =
        strategy.equals("paragraph") ? text.split("\\n\\s*\\n") : text.split("(?m)(?=^#{1,6}\\s+)");
    List<String> out = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    for (String unit : units) {
      if (unit.isBlank()) continue;
      if (part.length() + unit.length() > 1000 && !part.isEmpty()) {
        out.add(part.toString().trim());
        part.setLength(0);
      }
      if (unit.codePointCount(0, unit.length()) > 1000) {
        out.addAll(fixed(unit, 1000, 0));
      } else {
        if (!part.isEmpty()) part.append("\n\n");
        part.append(unit);
      }
    }
    if (!part.isEmpty()) out.add(part.toString().trim());
    return out;
  }

  private static List<String> fixed(String text, int size, int overlap) {
    int[] chars = text.codePoints().toArray();
    List<String> out = new ArrayList<>();
    for (int i = 0; i < chars.length; i += size - overlap) {
      String c = new String(chars, i, Math.min(size, chars.length - i)).trim();
      if (!c.isBlank()) out.add(c);
      if (i + size >= chars.length) break;
    }
    return out;
  }

  public static String excerpt(String text, int length) {
    return text.codePointCount(0, text.length()) <= length
        ? text
        : text.substring(0, text.offsetByCodePoints(0, length));
  }
}
