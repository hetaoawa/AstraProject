package top.hetaoawa.astra.api;

import java.time.Instant;

/** A local rejection before transmission; exhausted long-term quotas are not queued overnight. */
public final class RateLimitException extends PlatformException {
  private final String rule;
  private final Instant retryAt;

  public RateLimitException(String rule, Instant retryAt) {
    super("Quota exhausted: " + rule, 0, "LOCAL_QUOTA_EXHAUSTED", "", true, null);
    this.rule = rule;
    this.retryAt = retryAt;
  }

  public String rule() {
    return rule;
  }

  public Instant retryAt() {
    return retryAt;
  }
}
