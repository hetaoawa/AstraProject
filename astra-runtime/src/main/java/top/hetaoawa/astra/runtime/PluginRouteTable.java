package top.hetaoawa.astra.runtime;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

final class PluginRouteTable {
  record Route(
      String owner,
      BotId bot,
      String method,
      String path,
      PathPattern pattern,
      HttpRoutes.Handler handler) {}

  private final Map<String, Route> routes = new ConcurrentHashMap<>();

  static void validate(String method, String path) {
    if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD").contains(method))
      throw new IllegalArgumentException("Unsupported HTTP method");
    if (path == null
        || !path.startsWith("/")
        || path.startsWith("//")
        || path.contains("?")
        || path.contains("#")
        || path.contains("\\")
        || path.contains("%")
        || Arrays.stream(path.split("/")).anyMatch(s -> s.equals("..") || s.equals(".")))
      throw new IllegalArgumentException("Invalid plugin route path");
    if (reserved(path)) throw new IllegalArgumentException("Framework route prefix is reserved");
    PathPatternParser.defaultInstance.parse(path);
  }

  static boolean reserved(String path) {
    return List.of("/qq", "/astra", "/actuator", "/error").stream()
        .anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  HttpRoutes.Handle register(
      String owner, BotId bot, String method, String path, HttpRoutes.Handler handler) {
    validate(method, path);
    Objects.requireNonNull(handler);
    String key = method + " " + path.replaceAll("\\{[^}]+}", "{variable}");
    var route =
        new Route(owner, bot, method, path, PathPatternParser.defaultInstance.parse(path), handler);
    if (routes.putIfAbsent(key, route) != null)
      throw new IllegalStateException(
          "Plugin route conflicts with an existing route: " + method + " " + path);
    return () -> routes.remove(key, route);
  }

  Optional<Route> find(String method, String path) {
    if (reserved(path)) return Optional.empty();
    var parsed = PathContainer.parsePath(path);
    return routes.values().stream()
        .filter(r -> r.method().equals(method) && r.pattern().matches(parsed))
        .sorted(
            Comparator.comparing(Route::pattern, PathPattern.SPECIFICITY_COMPARATOR)
                .thenComparing(Route::path))
        .findFirst();
  }

  Map<String, String> variables(Route route, String path) {
    var info = route.pattern().matchAndExtract(PathContainer.parsePath(path));
    return info == null ? Map.of() : info.getUriVariables();
  }

  int count() {
    return routes.size();
  }

  boolean active(Route route) {
    return routes.values().stream().anyMatch(r -> r == route);
  }
}
