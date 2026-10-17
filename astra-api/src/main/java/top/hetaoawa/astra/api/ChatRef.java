package top.hetaoawa.astra.api;

import java.util.Objects;

public record ChatRef(BotId bot, Scene scene, String id, String guildId) {
  public ChatRef {
    Objects.requireNonNull(bot);
    Objects.requireNonNull(scene);
    Objects.requireNonNull(id);
    guildId = guildId == null ? "" : guildId;
  }

  public String key() {
    return Keys.join(bot.appId(), bot.environment(), scene.name(), id);
  }
}
