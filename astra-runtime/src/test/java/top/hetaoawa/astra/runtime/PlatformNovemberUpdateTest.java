package top.hetaoawa.astra.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

class PlatformNovemberUpdateTest {
  @TempDir Path temp;
  private final BotId bot = new BotId("bot", "production");

  @Test
  void userInfoPreservesFalseAndUnknownAndReportsNonFriendErrors() throws Exception {
    var api = new FakeApi();
    var response =
        new ApiResponse(
            200,
            Map.of(
                "username",
                "昵称",
                "avatar",
                "https://example.com/avatar",
                "union_openid",
                "union",
                "allow_proactive_msg",
                false),
            "trace");
    api.answers.add(CompletableFuture.completedFuture(response));
    var info = api.userInfo("opaque/user").toCompletableFuture().get();
    assertEquals("v2_users_user_openid_info.get", api.calls.getFirst().operation());
    assertEquals(Map.of("user_openid", "opaque/user"), api.calls.getFirst().path());
    assertTrue(api.calls.getFirst().body().isEmpty());
    assertEquals("昵称", info.username());
    assertEquals("https://example.com/avatar", info.avatar());
    assertEquals("union", info.unionOpenid());
    assertEquals(false, info.allowProactiveMsg());
    assertSame(response, info.response());
    assertNull(UserInfo.from(new ApiResponse(200, Map.of(), "")).allowProactiveMsg());
    assertTrue(
        UserInfo.from(new ApiResponse(200, Map.of("allow_proactive_msg", true), ""))
            .allowProactiveMsg());
    var error =
        new PlatformException("No friend relationship", 400, "40184001", "trace", true, null);
    api.answers.add(CompletableFuture.failedFuture(error));
    assertSame(
        error,
        assertThrows(
                ExecutionException.class,
                () -> api.userInfo("not-friend").toCompletableFuture().get())
            .getCause());
    assertThrows(IllegalArgumentException.class, () -> api.userInfo(" "));
    assertEquals(2, api.calls.size());
  }

  @Test
  void userLookupCachesByRequestedC2cIdAndKeepsProactiveFalseWithoutInventingMembers() {
    try (var db = database("profile")) {
      var directory = new JdbcDirectory(db, List.of(new BotPolicy(bot, 1, 10, "", false)));
      var observer = new ApiObservations(directory, new JdbcHistory(db, false));
      var identity = new Identity(bot, EntityType.USER, "user_openid", "opaque/user");
      var job =
          new OutboundQueue.Job(
              "lookup",
              bot,
              "p",
              "GET",
              "/v2/users/opaque%2Fuser/info",
              Map.of(),
              Map.of(),
              null,
              null,
              Map.of(),
              Map.of(),
              0);
      observer.after(
          job,
          new ApiResponse(
              200,
              Map.of(
                  "username",
                  "昵称",
                  "avatar",
                  "https://example.com/avatar",
                  "allow_proactive_msg",
                  false,
                  "union_openid",
                  "union"),
              ""),
          null);
      var profile = directory.find(identity).orElseThrow();
      assertEquals(false, profile.fields().get("allow_proactive_msg"));
      assertEquals("昵称", profile.fields().get("username"));
      assertEquals("https://example.com/avatar", profile.fields().get("avatar"));
      assertTrue(
          directory
              .find(new Identity(bot, EntityType.USER, "member_openid", "opaque/user"))
              .isEmpty());
      assertTrue(directory.related(identity, 0, 20).items().isEmpty());
      observer.after(
          job, null, new PlatformException("No friend", 400, "40184001", "", true, null));
      assertEquals(profile, directory.find(identity).orElseThrow());
    }
  }

  @Test
  void userLookupUsesItsTenPerSecondWindowAndManagementCallsRemainIndependent() throws Exception {
    var tick = new AtomicLong(Instant.parse("2026-11-17T00:00:00Z").toEpochMilli());
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return Instant.ofEpochMilli(tick.get());
          }
        };
    try (var db = database("user-rate")) {
      var limits =
          new OutboundLimits(
              bot,
              new RouteCatalog(),
              OutboundLimits.Policy.defaults(),
              new JdbcQuotaStore(db),
              clock);
      for (int i = 0; i < 10; i++)
        try (var permit =
            limits.acquire("GET", "/v2/users/u" + i + "/info", Map.of(), false, () -> {})) {}
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var started = new CountDownLatch(1);
        var next =
            workers.submit(
                () -> {
                  started.countDown();
                  try (var permit =
                      limits.acquire("GET", "/v2/users/another/info", Map.of(), false, () -> {})) {}
                  return true;
                });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
        try (var permit = limits.acquire("GET", "/v2/media-images", Map.of(), false, () -> {})) {}
        tick.addAndGet(1000);
        assertTrue(next.get(3, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void allGroupEventModelsExposeUnionAndRecoverOlderStoredNotices() {
    var codec = new QqEventCodec();
    for (var type :
        List.of("GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE")) {
      var raw =
          Map.<String, Object>of(
              "group_openid",
              "group",
              "member_openid",
              "member",
              "op_member_openid",
              "operator",
              "union_group_openid",
              "union-group",
              "timestamp",
              1794873600);
      var event = (NoticeEvent) codec.normalize(bot, Map.of("t", type, "d", raw));
      if (event.memberChange() != null)
        assertEquals("union-group", event.memberChange().unionGroupOpenid());
      else
        assertEquals(
            "union-group", ((NoticePayload.GroupBotState) event.details()).unionGroupOpenid());
      assertEquals(event, codec.decode(codec.encode(event)));
      var stored = QqJson.map(codec.encode(event));
      QqJson.object(stored.get(event.memberChange() != null ? "memberChange" : "details"))
          .remove("unionGroupOpenid");
      assertEquals(event, codec.decode(QqJson.write(stored)));
    }
    try (var db = database("group-union")) {
      var other = new BotId("other", "production");
      var directory =
          new JdbcDirectory(
              db,
              List.of(
                  new BotPolicy(bot, 1, 10, "shared", true),
                  new BotPolicy(other, 1, 10, "shared", true)));
      directory.observe(
          codec.normalize(
              bot,
              Map.of(
                  "t",
                  "GROUP_ADD_ROBOT",
                  "d",
                  Map.of("group_openid", "g1", "union_group_openid", "same"))));
      directory.observe(
          codec.normalize(
              other,
              Map.of(
                  "t",
                  "GROUP_MEMBER_ADD",
                  "d",
                  Map.of(
                      "group_openid",
                      "g2",
                      "member_openid",
                      "user",
                      "union_group_openid",
                      "same"))));
      assertEquals(
          2,
          directory
              .related(new Identity(bot, EntityType.GROUP, "group_openid", "g1"), 0, 20)
              .items()
              .size());
      var observer = new ApiObservations(directory, new JdbcHistory(db, false));
      observer.after(
          new OutboundQueue.Job(
              "group",
              bot,
              "p",
              "GET",
              "/v2/groups/g3/info",
              Map.of(),
              Map.of(),
              null,
              null,
              Map.of(),
              Map.of(),
              0),
          new ApiResponse(200, Map.of("group_name", "群名", "union_group_openid", "same"), ""),
          null);
      assertEquals(
          3,
          directory
              .related(new Identity(bot, EntityType.GROUP, "group_openid", "g1"), 0, 20)
              .items()
              .size());
    }
  }

  @Test
  void currentC2cAndGroupMentionsWorkWithoutRemovedLegacyFieldsOrElementAuthors() {
    var codec = new QqEventCodec();
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
                        Map.of(
                            "id",
                            "u",
                            "user_openid",
                            "u",
                            "username",
                            "昵称",
                            "avatar",
                            "https://example.com/avatar",
                            "union_openid",
                            "union-u"),
                        "message_type",
                        103,
                        "msg_elements",
                        List.of(Map.of("content", "引用内容", "message_type", 0)))));
    assertEquals("https://example.com/avatar", c2c.message().content().author().avatar());
    assertNull(c2c.message().content().author().memberOpenid());
    assertNull(c2c.message().content().author().unionUserAccount());
    assertNull(c2c.message().content().elements().getFirst().author());
    var group =
        (MessageEvent)
            codec.normalize(
                bot,
                Map.of(
                    "t",
                    "GROUP_MESSAGE_CREATE",
                    "d",
                    Map.of(
                        "id",
                        "group-message",
                        "group_openid",
                        "g",
                        "author",
                        Map.of(
                            "member_openid",
                            "u",
                            "member_nickname",
                            "群名片",
                            "avatar",
                            "https://example.com/avatar"),
                        "mentions",
                        List.of(
                            Map.of(
                                "id",
                                "bot-id",
                                "member_openid",
                                "bot-id",
                                "is_you",
                                true,
                                "scope",
                                "single",
                                "member_nickname",
                                "机器人"),
                            Map.of("scope", "all", "is_you", false)))));
    var mention = group.message().content().mentions().getFirst();
    assertEquals(true, mention.isYou());
    assertEquals("single", mention.scope());
    assertEquals("机器人", mention.memberNickname());
    assertEquals("all", group.message().content().mentions().getLast().scope());
    assertEquals(group, codec.decode(codec.encode(group)));
  }

  @Test
  void removalOnlySendsMemberIdsAndCombinedHelperUsesTwoOrderedCalls() throws Exception {
    assertEquals(
        Map.of("member_openids", List.of("u")), new Moderation.RemoveMembers(List.of("u")).toMap());
    assertEquals(
        Map.of("member_openids", List.of("u")),
        new Moderation.RemoveMembers(List.of("u"), false).toMap());
    assertThrows(
        IllegalArgumentException.class, () -> new Moderation.RemoveMembers(List.of("u"), true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Moderation.RemoveMembers(Collections.nCopies(21, "u")));
    var api = new FakeApi();
    var first = new CompletableFuture<ApiResponse>();
    api.answers.add(first);
    var blacklist = new ApiResponse(200, Map.of("fail_openids", List.of("u2")), "blacklist");
    api.answers.add(CompletableFuture.completedFuture(blacklist));
    var result = api.moderation().kickAndBlacklist("g", List.of("u", "u2"));
    assertEquals(1, api.calls.size());
    assertFalse(api.calls.getFirst().body().containsKey("add_to_member_blacklist"));
    var removal = new ApiResponse(200, Map.of("remove_members_result", "success"), "remove");
    first.complete(removal);
    var done = result.toCompletableFuture().get();
    assertEquals("v2_groups_group_openid_member_blacklist.post", api.calls.getLast().operation());
    assertEquals(
        Map.of("op", "add", "member_openids", List.of("u", "u2")), api.calls.getLast().body());
    assertEquals(List.of("u2"), done.addToMemberBlacklistFailOpenids());
    assertSame(removal, done.response());
    assertSame(blacklist, done.blacklistResponse());
  }

  @Test
  void combinedHelperStopsWhenRemovalFailsAndRetainsRemovalWhenBlacklistFails() throws Exception {
    for (var code : List.of("40103011", "40103012")) {
      var api = new FakeApi();
      var error = new PlatformException("Removal disabled", 403, code, "trace", true, null);
      api.answers.add(CompletableFuture.failedFuture(error));
      assertSame(
          error,
          assertThrows(
                  ExecutionException.class,
                  () ->
                      api.moderation()
                          .kickAndBlacklist("g", List.of("u"))
                          .toCompletableFuture()
                          .get())
              .getCause());
      assertEquals(1, api.calls.size());
    }
    var api = new FakeApi();
    api.answers.add(
        CompletableFuture.completedFuture(
            new ApiResponse(200, Map.of("remove_members_result", "success"), "remove")));
    var denied =
        new PlatformException("Blacklist unavailable", 403, "11253", "blacklist", true, null);
    api.answers.add(CompletableFuture.failedFuture(denied));
    var failure =
        assertThrows(
            ExecutionException.class,
            () -> api.moderation().kickAndBlacklist("g", List.of("u")).toCompletableFuture().get());
    var partial =
        assertInstanceOf(Moderation.BlacklistAfterRemovalException.class, failure.getCause());
    assertEquals("success", partial.removal().removeMembersResult());
    assertEquals("remove", partial.removal().response().traceId());
    assertSame(denied, partial.getCause());
    assertEquals(2, api.calls.size());
  }

  private JdbcDatabase database(String name) {
    return new JdbcDatabase("sqlite", "jdbc:sqlite:" + temp.resolve(name + ".db"), "", "");
  }

  private record Call(String operation, Map<String, String> path, Map<String, Object> body) {}

  private static final class FakeApi implements PlatformApi {
    final List<Call> calls = new ArrayList<>();
    final Queue<CompletionStage<ApiResponse>> answers = new ArrayDeque<>();

    public CompletionStage<ApiResponse> call(
        String operation,
        Map<String, String> path,
        Map<String, Object> query,
        Map<String, Object> body) {
      calls.add(new Call(operation, path, body));
      return Objects.requireNonNull(answers.poll(), "Unexpected API call");
    }

    public Map<String, ApiOperation> operations() {
      return Map.of();
    }

    public CompletionStage<ApiResponse> raw(
        String method, String path, Map<String, Object> query, Map<String, Object> body) {
      throw new UnsupportedOperationException();
    }

    public CompletionStage<ApiResponse> multipart(
        String method, String path, Map<String, String> fields, Map<String, UploadFile> files) {
      throw new UnsupportedOperationException();
    }

    public CompletionStage<ApiResponse> uploadPart(
        String url, byte[] bytes, Map<String, String> headers) {
      throw new UnsupportedOperationException();
    }
  }
}
