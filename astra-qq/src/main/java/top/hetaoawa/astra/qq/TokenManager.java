package top.hetaoawa.astra.qq;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

public final class TokenManager {
  private final BotCredentials bot;
  private final HttpClient client;
  private final OutboundLimits limits;
  private String token;
  private Instant refreshAt = Instant.EPOCH;

  public TokenManager(BotCredentials bot, HttpClient client) {
    this(bot, client, null);
  }

  public TokenManager(BotCredentials bot, HttpClient client, OutboundLimits limits) {
    this.bot = bot;
    this.client = client;
    this.limits = limits;
  }

  public synchronized String token() {
    if (token != null && Instant.now().isBefore(refreshAt)) return token;
    var request =
        HttpRequest.newBuilder(bot.apiBase().resolve("/app/getAppAccessToken"))
            .timeout(bot.timeout())
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    QqJson.write(Map.of("appId", bot.bot().appId(), "clientSecret", bot.secret()))))
            .build();
    try (var permit =
        limits == null
            ? (OutboundLimits.Permit) () -> {}
            : limits.acquire("POST", "/app/getAppAccessToken", Map.of(), false, () -> {})) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var data = QqJson.map(response.body());
      if (limits != null)
        limits.feedback(
            "POST",
            "/app/getAppAccessToken",
            Map.of(),
            response.statusCode(),
            response.headers().map(),
            data,
            false);
      if (response.statusCode() / 100 != 2 || QqJson.string(data.get("access_token")).isBlank())
        throw new top.hetaoawa.astra.api.PlatformException(
            "Access token acquisition failed",
            response.statusCode(),
            QqJson.string(data.get("code")),
            response.headers().firstValue("X-Tps-Trace-Id").orElse(""),
            true,
            null);
      token = QqJson.string(data.get("access_token"));
      long ttl = QqJson.number(data.get("expires_in"), 7200);
      refreshAt = Instant.now().plusSeconds(Math.max(1, ttl > 120 ? ttl - 60 : ttl / 2));
      return token;
    } catch (top.hetaoawa.astra.api.PlatformException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new top.hetaoawa.astra.api.PlatformException(
          "Access token transport failed", 0, "", "", false, e);
    }
  }

  public synchronized void invalidate() {
    refreshAt = Instant.EPOCH;
  }
}
