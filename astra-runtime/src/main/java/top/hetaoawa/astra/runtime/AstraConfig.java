package top.hetaoawa.astra.runtime;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(value = "astra", ignoreUnknownFields = false)
public record AstraConfig(
    @DefaultValue Database database,
    @DefaultValue Redis redis,
    @DefaultValue History history,
    @DefaultValue Archive eventArchive,
    @DefaultValue Plugins plugins,
    @DefaultValue List<Bot> bots,
    @DefaultValue("data/astra-v1.lock") String lockFile,
    @DefaultValue("false") boolean console) {
  public AstraConfig {
    bots = bots == null ? List.of() : List.copyOf(bots);
  }

  public record Database(
      @DefaultValue("sqlite") String dialect,
      @DefaultValue("jdbc:sqlite:data/astra-v1.db") String url,
      @DefaultValue("") String username,
      @DefaultValue("") String password) {}

  public record Redis(
      @DefaultValue("redis://localhost:6379/0") String uri,
      @DefaultValue("astra:v1") String namespace,
      @DefaultValue("true") boolean enabled) {}

  public record History(
      @DefaultValue("false") boolean enabled, @DefaultValue("30") int retentionDays) {}

  public record Archive(
      @DefaultValue("false") boolean enabled, @DefaultValue("30") int retentionDays) {}

  public record Plugins(
      @DefaultValue("plugins") String directory,
      @DefaultValue("true") boolean enabledByDefault,
      Map<String, Map<String, Object>> configuration) {
    public Plugins {
      configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
  }

  public record Bot(
      String appId,
      @DefaultValue("production") String environment,
      String secret,
      @DefaultValue("WEBHOOK") String transport,
      @DefaultValue("https://api.bot.qq.com") String apiBase,
      @DefaultValue("8") int concurrency,
      @DefaultValue("10000") int queueCapacity,
      @DefaultValue("1000") int outboundCapacity,
      @DefaultValue RateLimits rateLimits,
      @DefaultValue("33554435") long intents,
      @DefaultValue("1") int shards,
      @DefaultValue("30s") java.time.Duration requestTimeout,
      @DefaultValue("5m") java.time.Duration signatureTolerance,
      @DefaultValue("false") boolean unionEnabled,
      @DefaultValue("") String unionScope) {
    public Bot {
      if (appId == null || appId.isBlank() || secret == null || secret.isBlank())
        throw new IllegalArgumentException("Bot app-id and secret are required");
      if (!Set.of("WEBHOOK", "WEBSOCKET").contains(transport)
          || concurrency < 1
          || queueCapacity < 1
          || outboundCapacity < 1
          || shards < 1
          || requestTimeout.isNegative()
          || requestTimeout.isZero()
          || signatureTolerance.isNegative()
          || unionEnabled && unionScope.isBlank())
        throw new IllegalArgumentException("Invalid bot configuration");
    }

    @Override
    public String toString() {
      return "Bot[" + appId + "/" + environment + "]";
    }
  }

  public record RateLimits(
      @DefaultValue("UNVERIFIED") top.hetaoawa.astra.qq.OutboundLimits.Certification certification,
      @DefaultValue("8") int concurrency,
      @DefaultValue("Asia/Shanghai") String dayZone,
      @DefaultValue("1s") java.time.Duration cooldown,
      @DefaultValue List<Budget> budgets) {
    public RateLimits {
      budgets = budgets == null ? List.of() : List.copyOf(budgets);
    }

    public top.hetaoawa.astra.qq.OutboundLimits.Policy policy() {
      return new top.hetaoawa.astra.qq.OutboundLimits.Policy(
          certification,
          concurrency,
          java.time.ZoneId.of(dayZone),
          cooldown,
          budgets.stream()
              .map(
                  b ->
                      new top.hetaoawa.astra.qq.OutboundLimits.ExtraRule(
                          b.id(), b.operation(), b.scope(), b.limit(), b.window(), b.daily()))
              .toList());
    }
  }

  public record Budget(
      String id,
      @DefaultValue("*") String operation,
      @DefaultValue("BOT") top.hetaoawa.astra.qq.OutboundLimits.Scope scope,
      int limit,
      @DefaultValue("1s") java.time.Duration window,
      @DefaultValue("false") boolean daily) {}
}
