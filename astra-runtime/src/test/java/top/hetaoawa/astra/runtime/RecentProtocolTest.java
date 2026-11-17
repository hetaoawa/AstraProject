package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.BotPolicy;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class RecentProtocolTest {
  @TempDir Path temp;

  @Test
  void suppliedGroupPacketExposesNicknameUnionAndEachKindOfIdentifier() throws Exception {
    var codec = new QqEventCodec();
    var bot = new BotId("bot", "production");
    var packet = currentGroupPacket();
    for (String type : List.of("GROUP_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE")) {
      packet.put("t", type);
      var event = (MessageEvent) codec.normalize(bot, packet);
      var content = event.message().content();
      assertEquals(Scene.GROUP, event.chat().scene());
      assertEquals("GROUP_MESSAGE_CREATE:sample-event", event.data().platformEventId());
      assertEquals("ROBOT1.0_sample-message!", event.message().id());
      assertEquals("REFIDX_sample/index==", event.message().index());
      assertEquals("sample-token_==", content.sceneContext().authToken());
      assertEquals("账号昵称", content.author().username());
      assertEquals("群内名片", content.author().memberNickname());
      assertNull(content.author().nick());
      assertEquals("owner", content.author().memberRole());
      assertEquals("group-example", content.metadata().groupId());
      assertEquals("group-example", content.metadata().groupOpenid());
      assertEquals("union-group-example", content.metadata().unionGroupOpenid());
      assertEquals(Instant.parse("2026-11-16T10:20:37Z"), event.data().occurredAt());
      assertEquals(event, codec.decode(codec.encode(event)));
      // Older queued records lack the newly typed components but retain the platform raw fields.
      var stored = QqJson.map(codec.encode(event));
      var storedContent = QqJson.object(QqJson.object(stored.get("message")).get("content"));
      QqJson.object(storedContent.get("author")).remove("memberNickname");
      QqJson.object(storedContent.get("metadata")).remove("unionGroupOpenid");
      assertEquals(event, codec.decode(QqJson.write(stored)));
    }
  }

  @Test
  void optionalGroupFieldsRemainAbsentInPrivateAndOlderChannelMessages() {
    var codec = new QqEventCodec();
    var bot = new BotId("bot", "production");
    var c2c =
        (MessageEvent)
            codec.normalize(
                bot,
                Map.of(
                    "t",
                    "C2C_MESSAGE_CREATE",
                    "d",
                    Map.of(
                        "id",
                        "private",
                        "author",
                        Map.of("user_openid", "user", "username", "账号昵称"))));
    assertNull(c2c.message().content().author().memberNickname());
    assertNull(c2c.message().content().metadata().unionGroupOpenid());
    var channel =
        (MessageEvent)
            codec.normalize(
                bot,
                Map.of(
                    "t",
                    "MESSAGE_CREATE",
                    "d",
                    Map.of(
                        "id",
                        "channel-message",
                        "channel_id",
                        "channel",
                        "guild_id",
                        "guild",
                        "author",
                        Map.of("id", "user"),
                        "member",
                        Map.of("nick", "频道昵称"))));
    assertEquals("频道昵称", channel.message().content().author().nick());
    assertNull(channel.message().content().author().memberNickname());
  }

  @Test
  void groupNicknamesStayInTheirOwnRelationshipsAndUnionStillRequiresConfiguration()
      throws Exception {
    var bot = new BotId("bot", "production");
    var other = new BotId("other", "production");
    var disabled = new BotId("disabled", "production");
    var codec = new QqEventCodec();
    try (var db =
        new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("sample-directory.db"), "", "")) {
      var directory =
          new JdbcDirectory(
              db,
              List.of(
                  new BotPolicy(bot, 1, 10, "shared", true),
                  new BotPolicy(other, 1, 10, "shared", true),
                  new BotPolicy(disabled, 1, 10, "shared", false)));
      var packet = currentGroupPacket();
      var first = (MessageEvent) codec.normalize(bot, packet);
      directory.upsert(
          first.actor(),
          Map.of("member_nickname", "旧版全局名片"),
          "older",
          first.data().occurredAt(),
          null,
          null,
          true);
      directory.observe(first);
      assertEquals(
          "群内名片",
          directory
              .relationships(first.chat(), false, 0, 20)
              .items()
              .getFirst()
              .fields()
              .get("member_nickname"));
      assertEquals("账号昵称", directory.find(first.actor()).orElseThrow().fields().get("username"));
      assertFalse(
          directory.find(first.actor()).orElseThrow().fields().containsKey("member_nickname"));

      var body = QqJson.object(packet.get("d"));
      body.put("group_openid", "another-group");
      body.put("group_id", "another-group");
      body.put("union_group_openid", "another-union-group");
      QqJson.object(body.get("author")).put("member_nickname", "另一个群的名片");
      var second = (MessageEvent) codec.normalize(bot, packet);
      directory.observe(second);
      assertEquals(
          "另一个群的名片",
          directory
              .relationships(second.chat(), false, 0, 20)
              .items()
              .getFirst()
              .fields()
              .get("member_nickname"));
      assertEquals(
          "群内名片",
          directory
              .relationships(first.chat(), false, 0, 20)
              .items()
              .getFirst()
              .fields()
              .get("member_nickname"));
      // Use the original supplied group on the other bots to exercise real observation, not direct
      // upsert.
      var otherPacket = currentGroupPacket();
      directory.observe(codec.normalize(other, otherPacket));
      directory.observe(codec.normalize(disabled, otherPacket));
      var group = new Identity(bot, EntityType.GROUP, "group_openid", "group-example");
      var related = directory.related(group, 0, 20).items();
      assertEquals(2, related.size());
      assertFalse(related.stream().anyMatch(profile -> profile.identity().bot().equals(disabled)));
      assertTrue(
          directory
              .related(
                  new Identity(disabled, EntityType.GROUP, "group_openid", "group-example"), 0, 20)
              .items()
              .isEmpty());
    }
  }

  private Map<String, Object> currentGroupPacket() throws Exception {
    try (var input = getClass().getResourceAsStream("/qq/group-message-current.json")) {
      return QqJson.map(
          new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void allThreeMessageEventsPreserveCurrentUserAttachmentSceneAndCardFields() {
    var codec = new QqEventCodec();
    var bot = new BotId("bot", "production");
    for (var type :
        List.of("GROUP_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "C2C_MESSAGE_CREATE")) {
      var raw = new LinkedHashMap<String, Object>();
      raw.put("id", "message");
      raw.put("timestamp", "2026-11-16T12:00:00+08:00");
      raw.put("content", "<@all> picture");
      raw.put("message_type", 3);
      raw.put(
          "author",
          Map.of(
              "id",
              "user",
              "username",
              "昵称",
              "avatar",
              "https://example.com/avatar",
              "bot",
              false,
              "member_role",
              "admin",
              "union_openid",
              "union",
              "union_user_account",
              "account",
              "member_openid",
              "",
              "user_openid",
              ""));
      if (type.startsWith("GROUP")) raw.put("group_openid", "group");
      raw.put("mentions", List.of(Map.of("id", "mention", "username", "被提及者")));
      raw.put(
          "attachments",
          List.of(
              Map.of(
                  "url",
                  "https://example.com/voice",
                  "content_type",
                  "voice",
                  "voice_wav_url",
                  "https://example.com/voice.wav",
                  "asr_refer_text",
                  "语音文字",
                  "size",
                  5_000_000_000L,
                  "future_attachment_field",
                  true)));
      raw.put(
          "message_scene",
          Map.of(
              "source",
              "default",
              "ext",
              List.of(
                  "msg_idx=REFIDX_abc==",
                  "ref_msg_idx=TMP_ref",
                  "auth_token=token==",
                  "future=a=b")));
      raw.put(
          "ark_data",
          Map.of(
              "ark_type",
              "picture",
              "ark_name",
              "图片",
              "prompt",
              "分享图片",
              "fields",
              Map.of(
                  "title", "标题", "preview", "https://example.com/picture", "tags", List.of("图片"))));
      raw.put("future_message_field", Map.of("value", 1));
      var event = (MessageEvent) codec.normalize(bot, Map.of("t", type, "d", raw));
      var content = event.message().content();
      assertEquals("user", content.author().identity().value());
      assertEquals("user", event.actor().value());
      assertEquals("https://example.com/avatar", content.author().avatar());
      assertEquals("account", content.author().unionUserAccount());
      assertEquals("mention", content.mentions().getFirst().identity().value());
      assertEquals(5_000_000_000L, content.attachments().getFirst().size());
      assertEquals("语音文字", content.attachments().getFirst().asrReferText());
      assertEquals("token==", content.sceneContext().authToken());
      assertEquals("a=b", content.sceneContext().extensions().get("future"));
      assertEquals("picture", content.ark().arkType());
      assertEquals("标题", content.ark().title());
      assertEquals("https://example.com/picture", content.ark().preview());
      assertEquals(raw, ((MessageEvent) codec.decode(codec.encode(event))).message().raw());
      assertEquals(content, ((MessageEvent) codec.decode(codec.encode(event))).message().content());
    }
  }

  @Test
  void parallelAndRecursiveMessagesUseCurrentElementsWithoutLegacyWrapper() {
    var codec = new QqEventCodec();
    var raw =
        Map.<String, Object>of(
            "id",
            "parallel",
            "group_openid",
            "group",
            "content",
            "",
            "message_type",
            101,
            "msg_idx",
            "top-level-index",
            "msg_elements",
            List.of(
                Map.of("content", "第一条", "message_type", 0),
                Map.of(
                    "content",
                    "",
                    "message_type",
                    102,
                    "msg_elements",
                    List.of(Map.of("content", "嵌套消息")))));
    var event =
        (MessageEvent)
            codec.normalize(
                new BotId("bot", "production"), Map.of("t", "GROUP_MESSAGE_CREATE", "d", raw));
    assertEquals("top-level-index", event.message().index());
    assertEquals(2, event.message().content().parallel().msgNodes().size());
    assertEquals("第一条\n嵌套消息", event.message().displayText());
    assertEquals(event, codec.decode(codec.encode(event)));
  }

  @Test
  void cardsUseTypeEightAndKeepTheirBodyWhenAddingAReference() {
    var payload =
        MessagePayload.card(
            MessagePayload.Card.tuwen(
                "标题", "说明", "https://example.com/detail", "https://example.com/image.png"));
    var referenced = payload.reference(new MessagePayload.Reference("REFIDX_abc", null));
    assertEquals(8, referenced.toMap().get("msg_type"));
    assertEquals(
        Map.of(
            "type",
            "tuwen",
            "content",
            Map.of(
                "title",
                "标题",
                "description",
                "说明",
                "url",
                "https://example.com/detail",
                "pic_url",
                "https://example.com/image.png")),
        referenced.toMap().get("card"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MessagePayload.Card.tuwen(
                "标题", "说明", "http://example.com", "https://example.com/image.png"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MessagePayload.Card.tuwen(
                "字".repeat(51), "说明", "https://example.com", "https://example.com/image.png"));
    var prepare =
        new MediaImagePayload.Prepare(
            "image.png", 100, "image/png", "a".repeat(32), "b".repeat(40));
    assertEquals(100L, prepare.toMap().get("file_size"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MediaImagePayload.Prepare(
                "image.png", 100, "image/jpeg", "a".repeat(32), "b".repeat(40)));
  }

  @Test
  void everyoneBudgetAppliesToPassiveTextAndMarkdownAndResetsTheNextDay() throws Exception {
    var bot = new BotId("bot", "production");
    var now = Instant.parse("2026-11-16T12:00:00Z");
    try (var db =
        new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve("everyone.db"), "", "")) {
      var store = new JdbcQuotaStore(db);
      var limits =
          new OutboundLimits(
              bot,
              new RouteCatalog(),
              OutboundLimits.Policy.defaults(),
              store,
              Clock.fixed(now, ZoneOffset.UTC));
      var text =
          Map.<String, Object>of("content", MessagePayload.AT_EVERYONE + " 通知", "msg_id", "one");
      try (var permit =
          limits.acquire("POST", "/v2/groups/group/messages", text, false, () -> {})) {}
      var markdown =
          Map.<String, Object>of(
              "markdown", Map.of("content", "<qqbot-at-everyone/> 通知"), "msg_id", "two");
      var failure =
          assertThrows(
              RateLimitException.class,
              () -> limits.acquire("POST", "/v2/groups/group/messages", markdown, false, () -> {}));
      assertEquals("group:at-everyone:daily", failure.rule());
      try (var permit =
          limits.acquire("POST", "/v2/groups/other/messages", markdown, false, () -> {})) {}
      var tomorrow =
          new OutboundLimits(
              bot,
              new RouteCatalog(),
              OutboundLimits.Policy.defaults(),
              store,
              Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC));
      try (var permit =
          tomorrow.acquire("POST", "/v2/groups/group/messages", markdown, false, () -> {})) {}
    }
  }
}
