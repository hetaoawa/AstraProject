package top.hetaoawa.astra.api;

import java.util.Objects;

public record Identity(BotId bot, EntityType type, String kind, String value) {
  public Identity {
    Objects.requireNonNull(bot);
    Objects.requireNonNull(type);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(value);
    if (value.isBlank() || kind.isBlank())
      throw new IllegalArgumentException("Empty platform identity");
  }

  public String key() {
    return Keys.join(bot.appId(), bot.environment(), type.name(), kind, value);
  }
}
