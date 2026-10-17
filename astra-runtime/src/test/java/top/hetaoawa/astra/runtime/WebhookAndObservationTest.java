package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class WebhookAndObservationTest {
  @TempDir Path temp;

  @Test
  void webhookAcknowledgesValidatedEventAndChallengeCannotSignEventPayload() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("astra.database.url", "jdbc:sqlite:" + temp.resolve("runtime.db"));
    properties.put("astra.redis.enabled", false);
    properties.put("astra.lock-file", temp.resolve("runtime.lock").toString());
    properties.put("astra.plugins.directory", temp.resolve("plugins").toString());
    properties.put("astra.bots[0].app-id", "11111111");
    properties.put("astra.bots[0].secret", "DG5g3B4j9X2KOErG");
    var config =
        new Binder(new MapConfigurationPropertySource(properties))
            .bind("astra", Bindable.of(AstraConfig.class))
            .get();
    var runtime = new AstraRuntime(config);
    try {
      var controller = new WebhookController(runtime);
      var challenge =
          Map.of(
              "op",
              13,
              "d",
              Map.of("event_ts", "1725442341", "plain_token", "Arq0D5A61EgUu4OxUvOp"));
      assertEquals(
          200,
          controller
              .receive("production", "11111111", request(QqJson.write(challenge), null, null))
              .getStatusCode()
              .value());
      String body =
          QqJson.write(
              Map.of(
                  "op",
                  0,
                  "id",
                  "event",
                  "t",
                  "GROUP_MESSAGE_CREATE",
                  "d",
                  Map.of(
                      "id",
                      "message",
                      "group_openid",
                      "group",
                      "author",
                      Map.of("member_openid", "user"),
                      "content",
                      "hello")));
      String ts = String.valueOf(Instant.now().getEpochSecond());
      var signature = new WebhookSignature(config.bots().getFirst().secret());
      assertEquals(
          401,
          controller
              .receive("production", "11111111", request(body, ts, "00"))
              .getStatusCode()
              .value());
      assertEquals(
          200,
          controller
              .receive("production", "11111111", request(body, ts, signature.sign(ts, body)))
              .getStatusCode()
              .value());
      var forged = Map.of("op", 13, "d", Map.of("event_ts", ts, "plain_token", body));
      assertEquals(
          400,
          controller
              .receive("production", "11111111", request(QqJson.write(forged), null, null))
              .getStatusCode()
              .value());
      assertEquals("UP", runtime.status().get("status"));
    } finally {
      runtime.destroy();
    }
  }

  private MockHttpServletRequest request(String body, String timestamp, String signature) {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Bot-Appid", "11111111");
    if (timestamp != null) request.addHeader("X-Signature-Timestamp", timestamp);
    if (signature != null) request.addHeader("X-Signature-Ed25519", signature);
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  @Test
  void outgoingFailuresStreamingAndSuccessfulLookupMaintainObservedData() {
    var bot = new BotId("bot", "production");
    var policy = List.of(new BotPolicy(bot, 1, 5, "", false));
    try (var db =
        new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("observations.db"), "", "")) {
      var directory = new JdbcDirectory(db, policy);
      var history = new JdbcHistory(db, true);
      var observer = new ApiObservations(directory, history);
      var job = job(bot, "failed", "POST", "/v2/groups/g/messages", Map.of("content", "attempt"));
      observer.before(job);
      observer.after(job, null, new PlatformException("timeout", 0, "", "", false, null));
      var record =
          history
              .find(new ChatRef(bot, Scene.GROUP, "g", ""), "outbound:failed", null)
              .orElseThrow();
      assertEquals("attempt", record.message().text());
      assertEquals("OUTCOME_UNKNOWN", record.processingState());
      var attachment =
          new OutboundQueue.Job(
              "attachment",
              bot,
              "plugin",
              "POST",
              "/channels/ch/messages",
              Map.of(),
              Map.of("content", "image"),
              null,
              null,
              Map.of(),
              Map.of(
                  "file_image", new UploadFile("example.png", "image/png", new byte[] {1, 2, 3})),
              0);
      observer.before(attachment);
      observer.after(
          attachment,
          new ApiResponse(
              200, Map.of("id", "with-image", "ext_info", Map.of("ref_idx", "REFIDX_example")), ""),
          null);
      var sent =
          history
              .find(new ChatRef(bot, Scene.CHANNEL, "ch", ""), "with-image", "REFIDX_example")
              .orElseThrow();
      assertTrue(
          sent.message().segments().stream()
              .anyMatch(
                  segment ->
                      segment.type().equals("attachment")
                          && segment.data().get("filename").equals("example.png")
                          && segment.data().get("size").equals(3)));
      var lookup = job(bot, "lookup", "GET", "/v2/groups/g/info", Map.of());
      observer.after(lookup, new ApiResponse(200, Map.of("group_name", "Study Group"), ""), null);
      assertEquals(1, directory.search(bot, EntityType.GROUP, "STUDY", 0, 20).items().size());
      assertEquals(0, directory.search(bot, EntityType.GROUP, "_", 0, 20).items().size());
      var first =
          job(
              bot,
              "stream1",
              "POST",
              "/v2/users/u/stream_messages",
              Map.of("input_mode", "append", "content_raw", "hello"));
      var next =
          job(
              bot,
              "stream2",
              "POST",
              "/v2/users/u/stream_messages",
              Map.of("input_mode", "append", "content_raw", " world"));
      observer.before(first);
      observer.after(first, new ApiResponse(200, Map.of("id", "stream"), ""), null);
      observer.before(next);
      observer.after(next, new ApiResponse(200, Map.of("id", "stream"), ""), null);
      observer.after(next, new ApiResponse(200, Map.of("id", "stream"), ""), null);
      assertEquals(
          "hello world",
          history
              .find(new ChatRef(bot, Scene.C2C, "u", ""), "stream", null)
              .orElseThrow()
              .message()
              .text());
    }
  }

  private OutboundQueue.Job job(
      BotId bot, String id, String method, String path, Map<String, Object> body) {
    return new OutboundQueue.Job(
        id, bot, "plugin", method, path, Map.of(), body, null, null, Map.of(), Map.of(), 0);
  }
}
