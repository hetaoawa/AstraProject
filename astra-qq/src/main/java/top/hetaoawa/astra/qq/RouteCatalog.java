package top.hetaoawa.astra.qq;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RouteCatalog {
  public record Operation(
      String id, String method, String template, String title, String documentation) {}

  private final Map<String, Operation> routes;

  public RouteCatalog() {
    try (var input = getClass().getResourceAsStream("/qq/endpoints.tsv")) {
      if (input == null) throw new IllegalStateException("Endpoint catalog missing");
      Map<String, Operation> result = new LinkedHashMap<>();
      for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] parts = line.split("\\t", -1);
        if (parts.length != 5) throw new IllegalStateException("Malformed endpoint catalog");
        var op = new Operation(parts[0], parts[1], parts[2], parts[3], parts[4]);
        if (result.putIfAbsent(op.id(), op) != null)
          throw new IllegalStateException("Duplicate operation " + op.id());
      }
      routes = Collections.unmodifiableMap(result);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Operation> operations() {
    return routes;
  }

  public Operation require(String id) {
    var result = routes.get(id);
    if (result == null) throw new IllegalArgumentException("Unknown QQ operation: " + id);
    return result;
  }

  public String path(Operation operation, Map<String, String> params) {
    return expand(operation.template(), params);
  }

  public static String expand(String template, Map<String, String> params) {
    var matcher = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(template);
    StringBuffer out = new StringBuffer();
    Set<String> used = new HashSet<>();
    while (matcher.find()) {
      String name = matcher.group(1), value = params.get(name);
      if (value == null || value.isBlank())
        throw new IllegalArgumentException("Missing path parameter " + name);
      used.add(name);
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(encode(value)));
    }
    matcher.appendTail(out);
    if (!used.containsAll(params.keySet()))
      throw new IllegalArgumentException("Unknown path parameter");
    return out.toString();
  }

  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
