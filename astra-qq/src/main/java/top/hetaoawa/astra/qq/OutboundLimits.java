package top.hetaoawa.astra.qq;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.regex.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.QuotaStore;

/** Per-operation admission plus the platform's layered message budgets. No HTTP retries. */
public final class OutboundLimits {
  public enum Certification {
    UNVERIFIED,
    PERSONAL,
    ENTERPRISE
  }

  public enum Scope {
    BOT,
    TARGET
  }

  public record ExtraRule(
      String id, String operation, Scope scope, int limit, Duration window, boolean daily) {
    public ExtraRule {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(window);
      if (id == null
          || id.isBlank()
          || operation == null
          || operation.isBlank()
          || limit < 1
          || window.toMillis() < 1
          || window.isNegative()) throw new IllegalArgumentException("Invalid outbound budget");
    }
  }

  public record Policy(
      Certification certification,
      int concurrency,
      ZoneId dayZone,
      Duration cooldown,
      List<ExtraRule> extraRules) {
    public Policy {
      Objects.requireNonNull(certification);
      Objects.requireNonNull(dayZone);
      Objects.requireNonNull(cooldown);
      extraRules = List.copyOf(extraRules);
      if (concurrency < 1 || concurrency > 128 || cooldown.isNegative() || cooldown.isZero())
        throw new IllegalArgumentException("Invalid outbound policy");
      if (extraRules.stream().map(ExtraRule::id).distinct().count() != extraRules.size())
        throw new IllegalArgumentException("Duplicate quota IDs");
    }

    public static Policy defaults() {
      return new Policy(
          Certification.UNVERIFIED,
          8,
          ZoneId.of("Asia/Shanghai"),
          Duration.ofSeconds(1),
          List.of());
    }
  }

  public record RouteRule(int count, long windowMillis, int burst, String evidence) {}

  private record Route(RouteCatalog.Operation operation, Pattern pattern, List<String> names) {}

  public record Request(
      String operation, Map<String, String> parameters, String target, boolean external) {}

  private record Window(String key, int count, long millis, int burst) {}

  private record Plan(
      Request request, List<Window> windows, List<QuotaStore.Charge> charges, long resetAt) {}

  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  public interface Guard extends Runnable {
    void check();

    boolean ready();
  }

  private final BotId bot;
  private final Policy policy;
  private final QuotaStore store;
  private final Clock clock;
  private final Semaphore permits;
  private final List<Route> routes = new ArrayList<>();
  private final Map<String, RouteRule> rules = new LinkedHashMap<>();
  private final Map<String, String> routeKeys = new HashMap<>();
  private final LinkedHashMap<String, QuotaStore.State> cache =
      new LinkedHashMap<>(128, 0.75f, true);
  private BiFunction<String, String, Map<String, Object>> resolver;

  public OutboundLimits(BotId bot, RouteCatalog catalog, Policy policy, QuotaStore store) {
    this(bot, catalog, policy, store, Clock.systemUTC());
  }

  public OutboundLimits(
      BotId bot, RouteCatalog catalog, Policy policy, QuotaStore store, Clock clock) {
    this.bot = bot;
    this.policy = policy;
    this.store = store;
    this.clock = clock;
    permits = new Semaphore(policy.concurrency(), true);
    try (var in = getClass().getResourceAsStream("/qq/rate-limits.tsv")) {
      if (in == null) throw new IllegalStateException("QQ quota catalog missing");
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] p = line.split("\\t", -1);
        rules.put(
            p[0],
            new RouteRule(
                Integer.parseInt(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3]), p[4]));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    if (!rules.keySet().equals(catalog.operations().keySet()))
      throw new IllegalStateException("QQ quota catalog does not cover all operations");
    catalog
        .operations()
        .forEach(
            (id, operation) -> routeKeys.put(id, operation.method() + " " + operation.template()));
    catalog.operations().values().stream()
        .sorted(
            Comparator.comparingInt(
                    (RouteCatalog.Operation o) -> o.template().replaceAll("\\{[^}]+}", "").length())
                .reversed())
        .forEach(
            o -> {
              Matcher m = Pattern.compile("\\{([^}]+)}").matcher(o.template());
              StringBuilder p = new StringBuilder("^");
              List<String> names = new ArrayList<>();
              int end = 0;
              while (m.find()) {
                p.append(Pattern.quote(o.template().substring(end, m.start()))).append("([^/]+)");
                names.add(m.group(1));
                end = m.end();
              }
              p.append(Pattern.quote(o.template().substring(end))).append('$');
              routes.add(new Route(o, Pattern.compile(p.toString()), List.copyOf(names)));
            });
    for (var rule : policy.extraRules())
      if (!rule.operation().equals("*")
          && !rules.containsKey(rule.operation())
          && !Set.of("access_token", "upload_part", "unknown").contains(rule.operation()))
        throw new IllegalArgumentException("Unknown quota operation: " + rule.operation());
  }

  public Map<String, RouteRule> rules() {
    return Collections.unmodifiableMap(rules);
  }

  public void resolver(BiFunction<String, String, Map<String, Object>> resolver) {
    this.resolver = resolver;
  }

  public Request classify(String method, String path, boolean external) {
    if (external) return new Request("upload_part", Map.of(), "upload", true);
    if (path.equals("/app/getAppAccessToken"))
      return new Request("access_token", Map.of(), "bot", false);
    for (var r : routes) {
      if (!r.operation().method().equals(method)) continue;
      var m = r.pattern().matcher(path);
      if (!m.matches()) continue;
      Map<String, String> p = new LinkedHashMap<>();
      for (int i = 0; i < r.names().size(); i++)
        p.put(
            r.names().get(i),
            URLDecoder.decode(m.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
      String target = r.names().isEmpty() ? "bot" : p.get(r.names().getFirst());
      return new Request(r.operation().id(), Map.copyOf(p), target, false);
    }
    return new Request("unknown", Map.of(), method + " " + path, false);
  }

  /**
   * Management operations and uploads have independent lanes; message streams retain chat order.
   */
  public String lane(String method, String path, boolean external) {
    return lane(method, path, Map.of(), external);
  }

  public String lane(String method, String path, Map<String, Object> body, boolean external) {
    Request r = classify(method, path, external);
    boolean message =
        method.equals("POST")
            && (path.endsWith("/messages")
                || path.endsWith("/stream_messages")
                || path.endsWith("/files")
                    && Boolean.parseBoolean(String.valueOf(body.get("srv_send_msg"))));
    String scene =
        path.startsWith("/v2/groups/")
            ? "group"
            : path.startsWith("/v2/users/") ? "c2c" : path.startsWith("/dms/") ? "dm" : "channel";
    return Keys.join(
        bot.key(), message ? "message:" + scene : method + ":" + r.operation(), r.target());
  }

  public Permit acquire(
      String method,
      String path,
      Map<String, Object> body,
      boolean external,
      Runnable beforeTransmission)
      throws InterruptedException {
    if (beforeTransmission instanceof Guard guard) guard.check();
    Plan plan = plan(classify(method, path, external), body);
    reject(plan, store.check(plan.charges(), clock.millis()));
    while (true) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      if (clock.millis() >= plan.resetAt()) {
        plan = plan(plan.request(), body);
        reject(plan, store.check(plan.charges(), clock.millis()));
      }
      if (beforeTransmission instanceof Guard guard) {
        guard.check();
        if (!guard.ready()) {
          Thread.sleep(250);
          continue;
        }
      }
      long now = clock.millis(), wait;
      synchronized (this) {
        wait = delay(plan, now);
        if (wait == 0 && permits.tryAcquire()) {
          try {
            var denied = store.reserve(plan.charges(), now);
            reject(plan, denied);
            commit(plan.windows(), now);
            beforeTransmission.run();
            return permits::release;
          } catch (Throwable e) {
            permits.release();
            throw e;
          }
        }
      }
      Thread.sleep(Math.max(1, Math.min(wait == 0 ? 10 : wait, 250)));
    }
  }

  private static void reject(Plan plan, QuotaStore.Denied denied) {
    if (denied == null) return;
    var charge =
        plan.charges().stream().filter(c -> c.key().equals(denied.key())).findFirst().orElseThrow();
    throw new RateLimitException(charge.rule(), Instant.ofEpochMilli(denied.expiresAt()));
  }

  private String key(String rule, String target) {
    return Keys.join(bot.key(), rule, target);
  }

  private void rate(List<Window> w, String rule, String target, int count, long millis) {
    w.add(new Window(key(rule, target), count, millis, 0));
  }

  private void daily(
      List<QuotaStore.Charge> q, String rule, String target, int count, long reset, String member) {
    q.add(
        new QuotaStore.Charge(
            key(rule, target + ":" + Instant.ofEpochMilli(reset)),
            count,
            reset,
            member == null ? null : Keys.join(member),
            rule));
  }

  private Plan plan(Request r, Map<String, Object> body) {
    long now = clock.millis(),
        reset =
            Instant.ofEpochMilli(now)
                .atZone(policy.dayZone())
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(policy.dayZone())
                .toInstant()
                .toEpochMilli();
    List<Window> w = new ArrayList<>();
    List<QuotaStore.Charge> q = new ArrayList<>();
    var route = rules.get(r.operation());
    if (route != null && route.count() > 0)
      w.add(
          new Window(
              key("route:" + r.operation(), "bot"),
              route.count(),
              route.windowMillis(),
              route.burst()));
    if (route != null && route.burst() > 0)
      rate(w, "route:burst:" + r.operation(), "bot", route.burst(), route.windowMillis());
    boolean passive =
        !QqJson.string(body.get("msg_id")).isBlank()
            || !QqJson.string(body.get("event_id")).isBlank();
    boolean stream = r.operation().equals("v2_users_user_openid_stream_messages.post");
    boolean autoSend = Boolean.parseBoolean(String.valueOf(body.get("srv_send_msg")));
    boolean
        c2c =
            r.operation().equals("v2_users_user_openid_messages.post")
                || stream
                || (autoSend && r.operation().equals("v2_users_user_openid_files.post")),
        group =
            r.operation().equals("v2_groups_group_openid_messages.post")
                || (autoSend && r.operation().equals("v2_groups_group_openid_files.post"));
    if ((c2c || group) && (!stream || QqJson.string(body.get("stream_msg_id")).isBlank())) {
      if (c2c && Boolean.parseBoolean(String.valueOf(body.get("is_wakeup")))) {
        var interaction = store.load(key("interaction", r.target()), now);
        if (interaction == null)
          throw new PlatformException(
              "No observed user interaction within the recall window",
              0,
              "RECALL_CONTEXT_UNKNOWN",
              "",
              true,
              null);
        long start = Long.parseLong(interaction.value());
        long days =
            ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(start).atZone(policy.dayZone()).toLocalDate(),
                Instant.ofEpochMilli(now).atZone(policy.dayZone()).toLocalDate());
        if (days < 0 || days >= 30)
          throw new PlatformException(
              "Interaction recall expired", 0, "RECALL_EXPIRED", "", true, null);
        String period = days == 0 ? "0" : days < 3 ? "1-3" : days < 7 ? "3-7" : "7-30";
        long expiry =
            Instant.ofEpochMilli(start)
                .atZone(policy.dayZone())
                .toLocalDate()
                .plusDays(days == 0 ? 1 : days < 3 ? 3 : days < 7 ? 7 : 30)
                .atStartOfDay(policy.dayZone())
                .toInstant()
                .toEpochMilli();
        q.add(
            new QuotaStore.Charge(
                key("recall", Keys.join(r.target(), Long.toString(start), period)),
                1,
                expiry,
                null,
                "recall:" + period));
      } else if (passive) {
        String mid = QqJson.string(body.get("msg_id"));
        if (!mid.isBlank())
          q.add(
              new QuotaStore.Charge(
                  key("passive:" + (c2c ? "c2c" : "group"), Keys.join(r.target(), mid)),
                  c2c ? 4 : 5,
                  now + Duration.ofMinutes(c2c ? 60 : 5).toMillis(),
                  null,
                  "passive:" + (c2c ? "c2c" : "group")));
      } else {
        String scene = c2c ? "c2c" : "group";
        boolean certified = policy.certification() != Certification.UNVERIFIED;
        if (c2c) rate(w, "active:c2c:second", "bot", certified ? 10 : 5, 1000);
        if (group || !certified)
          rate(w, "active:" + scene + ":minute", "bot", certified ? 60 : 30, 60000);
        rate(w, "active:" + scene + ":relation", r.target(), 20, 60000);
        daily(q, "active:" + scene + ":daily", r.target(), 1000, reset, null);
      }
    }
    if (r.operation().equals("send")) {
      rate(w, "channel:messages", r.target(), 5, 1000);
      if (!passive) {
        String guild = channelGuild(r.target());
        Map<String, Object> setting = setting(guild);
        if (Boolean.parseBoolean(String.valueOf(setting.get("disable_push_msg"))))
          throw new PlatformException(
              "Guild disables active messages", 0, "ACTIVE_MESSAGE_DISABLED", "", true, null);
        List<String> ids =
            QqJson.array(setting.get("channel_ids")).stream().map(String::valueOf).toList();
        if (!ids.isEmpty() && !ids.contains(r.target()))
          throw new PlatformException(
              "Channel is not enabled for active messages",
              0,
              "ACTIVE_CHANNEL_DISABLED",
              "",
              true,
              null);
        int maximum = (int) QqJson.number(setting.get("channel_push_max_num"), 20);
        daily(q, "channel:active", r.target(), maximum, reset, null);
        daily(q, "guild:active:channels", guild, ids.isEmpty() ? 2 : ids.size(), reset, r.target());
      }
    }
    if (r.operation().equals("dms.post.dms_guild_id_messages") && !passive) {
      var user = store.load(key("dm:user", r.target()), now);
      if (user == null)
        throw new PlatformException(
            "DM recipient is unknown; observe a DM event or create the session first",
            0,
            "DM_RECIPIENT_UNKNOWN",
            "",
            true,
            null);
      daily(q, "dm:active:user", user.value(), 2, reset, null);
      daily(q, "dm:active:bot", "bot", 200, reset, null);
    }
    if (r.operation().equals("post_schedule")) {
      daily(q, "schedule:admin", "bot", 10, reset, null);
      daily(q, "schedule:guild", channelGuild(r.target()), 100, reset, null);
    }
    if (r.operation().equals("post_api_permission_demand"))
      daily(q, "permission:demand", r.parameters().get("guild_id"), 3, reset, null);
    for (var extra : policy.extraRules()) {
      if (!extra.operation().equals("*") && !sameOperation(extra.operation(), r.operation()))
        continue;
      // A presigned COS upload is not an OpenAPI call; only an explicit upload_part rule applies.
      if (r.external() && extra.operation().equals("*")) continue;
      String target = extra.scope() == Scope.BOT ? "bot" : r.target();
      if (extra.daily()) daily(q, "configured:" + extra.id(), target, extra.limit(), reset, null);
      else rate(w, "configured:" + extra.id(), target, extra.limit(), extra.window().toMillis());
    }
    return new Plan(r, List.copyOf(w), List.copyOf(q), reset);
  }

  private boolean sameOperation(String left, String right) {
    return left.equals(right)
        || routeKeys.containsKey(left) && routeKeys.get(left).equals(routeKeys.get(right));
  }

  private long delay(Plan plan, long now) {
    long delay = 0;
    var pause = loadRate(key("cooldown", cooldownScope(plan.request())), now);
    if (pause != null) delay = pause.expiresAt() - now;
    for (var w : plan.windows()) {
      var state = loadRate(w.key(), now);
      if (state == null) continue;
      var data = QqJson.map(state.value());
      if (w.burst() > 0) {
        double tokens =
            QqJson.number(data.get("tokensMicros"), w.burst() * 1000000L) / 1000000d
                + (now - QqJson.number(data.get("updated"), now)) * w.count() / (double) w.millis();
        if (tokens < 1)
          delay = Math.max(delay, (long) Math.ceil((1 - tokens) * w.millis() / w.count()));
      } else {
        List<Long> times =
            QqJson.array(data.get("times")).stream()
                .map(v -> QqJson.number(v, 0))
                .filter(t -> t > now - w.millis())
                .toList();
        if (times.size() >= w.count())
          delay = Math.max(delay, times.get(times.size() - w.count()) + w.millis() - now);
      }
    }
    return Math.max(0, delay);
  }

  private static String cooldownScope(Request request) {
    return request.operation().equals("unknown") ? request.target() : request.operation();
  }

  private void commit(List<Window> windows, long now) {
    for (var w : windows) {
      var old = loadRate(w.key(), now);
      var data = old == null ? Map.<String, Object>of() : QqJson.map(old.value());
      if (w.burst() > 0) {
        double tokens =
            Math.min(
                    w.burst(),
                    QqJson.number(data.get("tokensMicros"), w.burst() * 1000000L) / 1000000d
                        + (now - QqJson.number(data.get("updated"), now))
                            * w.count()
                            / (double) w.millis())
                - 1;
        saveRate(
            w.key(),
            QqJson.write(Map.of("tokensMicros", (long) (tokens * 1000000), "updated", now)),
            now + w.millis() * w.burst());
      } else {
        List<Long> times =
            new ArrayList<>(
                QqJson.array(data.get("times")).stream()
                    .map(v -> QqJson.number(v, 0))
                    .filter(t -> t > now - w.millis())
                    .toList());
        times.add(now);
        saveRate(w.key(), QqJson.write(Map.of("times", times)), now + w.millis());
      }
    }
  }

  private QuotaStore.State loadRate(String key, long now) {
    var state = cache.get(key);
    if (state != null && state.expiresAt() > now) return state;
    cache.remove(key);
    state = store.load(key, now);
    if (state != null) cacheRate(key, state);
    return state;
  }

  private void saveRate(String key, String value, long expiresAt) {
    store.save(key, value, expiresAt);
    cacheRate(key, new QuotaStore.State(value, expiresAt));
  }

  private void cacheRate(String key, QuotaStore.State state) {
    if (cache.size() >= 10000 && !cache.containsKey(key))
      cache.remove(cache.keySet().iterator().next());
    cache.put(key, state);
  }

  private String channelGuild(String channel) {
    var state = store.load(key("channel:guild", channel), clock.millis());
    if (state == null) {
      if (resolver == null)
        throw new IllegalStateException("Channel metadata resolver unavailable");
      resolver.apply("GET", "/channels/" + RouteCatalog.encode(channel));
      state = store.load(key("channel:guild", channel), clock.millis());
    }
    if (state == null)
      throw new PlatformException(
          "Channel guild ID unavailable for quota accounting",
          0,
          "GUILD_QUOTA_CONTEXT_UNKNOWN",
          "",
          true,
          null);
    return state.value();
  }

  private Map<String, Object> setting(String guild) {
    var state = store.load(key("guild:setting", guild), clock.millis());
    if (state == null) {
      if (resolver == null) throw new IllegalStateException("Guild setting resolver unavailable");
      resolver.apply("GET", "/guilds/" + RouteCatalog.encode(guild) + "/message/setting");
      state = store.load(key("guild:setting", guild), clock.millis());
    }
    if (state == null)
      throw new PlatformException(
          "Guild message settings unavailable", 0, "GUILD_QUOTA_SETTINGS_UNKNOWN", "", true, null);
    return QqJson.map(state.value());
  }

  public synchronized void observe(Event event) {
    long now = clock.millis();
    if (event instanceof MessageEvent m) {
      var chat = m.data().chat();
      if (chat.scene() == Scene.C2C) {
        String k = key("interaction", chat.id());
        var old = store.load(k, now);
        long at = m.data().occurredAt().toEpochMilli();
        if (old == null || Long.parseLong(old.value()) < at)
          store.save(k, Long.toString(at), at + Duration.ofDays(31).toMillis());
      }
      if (chat.scene() == Scene.CHANNEL && !chat.guildId().isBlank())
        store.save(
            key("channel:guild", chat.id()), chat.guildId(), now + Duration.ofDays(30).toMillis());
      if (chat.scene() == Scene.CHANNEL_DM && m.data().actor() != null)
        store.save(
            key("dm:user", chat.id()),
            m.data().actor().value(),
            now + Duration.ofDays(30).toMillis());
    }
  }

  public synchronized void feedback(
      String method,
      String path,
      Map<String, Object> request,
      int status,
      Map<String, List<String>> headers,
      Map<String, Object> data,
      boolean external) {
    long now = clock.millis();
    Request r = classify(method, path, external);
    String code = QqJson.string(data.getOrDefault("err_code", data.get("code")));
    boolean success = status / 100 == 2 && (code.isBlank() || code.equals("0"));
    if (success) {
      if (method.equals("GET")
          && path.startsWith("/channels/")
          && !QqJson.string(data.get("guild_id")).isBlank())
        store.save(
            key("channel:guild", r.target()),
            QqJson.string(data.get("guild_id")),
            now + Duration.ofDays(30).toMillis());
      if (r.operation().equals("message_setting"))
        store.save(
            key("guild:setting", r.target()),
            QqJson.write(data),
            now + Duration.ofMinutes(5).toMillis());
      if (method.equals("POST")
          && path.equals("/users/@me/dms")
          && !QqJson.string(data.get("guild_id")).isBlank()
          && !QqJson.string(request.get("recipient_id")).isBlank())
        store.save(
            key("dm:user", QqJson.string(data.get("guild_id"))),
            QqJson.string(request.get("recipient_id")),
            now + Duration.ofDays(30).toMillis());
    }
    if (status == 429
        || Set.of(
                "100001",
                "40034100",
                "20028",
                "304045",
                "304049",
                "304050",
                "304052",
                "503004",
                "610013",
                "620006",
                "1100100",
                "1100308")
            .contains(code)) {
      long until = now + policy.cooldown().toMillis();
      String retry =
          headers.entrySet().stream()
              .filter(e -> e.getKey().equalsIgnoreCase("Retry-After"))
              .flatMap(e -> e.getValue().stream())
              .findFirst()
              .orElse("");
      try {
        until = Math.max(until, now + (long) (Double.parseDouble(retry) * 1000));
      } catch (NumberFormatException e) {
        try {
          until =
              Math.max(
                  until,
                  ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                      .toInstant()
                      .toEpochMilli());
        } catch (java.time.format.DateTimeParseException ignored) {
        }
      }
      var old = store.load(key("cooldown", cooldownScope(r)), now);
      if (old != null) until = Math.max(until, old.expiresAt());
      saveRate(key("cooldown", cooldownScope(r)), code, until);
    }
  }
}
