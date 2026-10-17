package top.hetaoawa.astra.api;

import java.util.Objects;

public record BotId(String appId, String environment) {
  public BotId {
    Objects.requireNonNull(appId);
    Objects.requireNonNull(environment);
    if (appId.isBlank() || environment.isBlank())
      throw new IllegalArgumentException("Empty bot identity");
  }

  public String key() {
    return Keys.join(appId, environment);
  }
}
