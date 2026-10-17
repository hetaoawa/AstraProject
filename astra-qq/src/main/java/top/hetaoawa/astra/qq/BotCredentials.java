package top.hetaoawa.astra.qq;

import java.net.URI;
import java.time.Duration;
import top.hetaoawa.astra.api.BotId;

public record BotCredentials(BotId bot, String secret, URI apiBase, Duration timeout) {
  public BotCredentials {
    if (secret == null || secret.isBlank())
      throw new IllegalArgumentException("Secret required for " + bot.appId());
    if (apiBase.getHost() == null
        || !java.util.Set.of("http", "https").contains(apiBase.getScheme()))
      throw new IllegalArgumentException("Invalid API base");
  }

  @Override
  public String toString() {
    return "BotCredentials[bot=" + bot + ", secret=REDACTED, apiBase=" + apiBase + "]";
  }
}
