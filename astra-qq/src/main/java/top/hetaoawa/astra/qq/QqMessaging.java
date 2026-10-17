package top.hetaoawa.astra.qq;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

public final class QqMessaging implements Messaging {
  private final String owner;
  private final BotId bot;
  private final OutboundQueue queue;
  private final OutboundStore store;

  public QqMessaging(String owner, BotId bot, OutboundQueue queue, OutboundStore store) {
    this.owner = owner;
    this.bot = bot;
    this.queue = queue;
    this.store = store;
  }

  public CompletionStage<ApiResponse> send(ChatRef chat, Map<String, Object> body) {
    try {
      check(chat);
      if (body.containsKey("msg_id") || body.containsKey("event_id"))
        throw new IllegalArgumentException(
            "Use reply(source, body) for passive replies, or raw API with an explicit validity"
                + " deadline policy");
      return enqueue(chat, body, 0, "POST", null);
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public CompletionStage<ApiResponse> reply(MessageEvent source, Map<String, Object> body) {
    try {
      check(source.chat());
      Scene scene = source.chat().scene();
      long seconds = scene == Scene.C2C ? 3600 : 300;
      long deadline = source.data().occurredAt().plusSeconds(seconds).toEpochMilli();
      if (System.currentTimeMillis() > deadline)
        throw new PlatformException("Passive reply expired", 0, "REPLY_EXPIRED", "", true, null);
      Map<String, Object> request = new LinkedHashMap<>(body);
      request.put("msg_id", source.message().id());
      request.remove("event_id");
      if (scene == Scene.GROUP || scene == Scene.C2C) {
        int seq =
            store.nextReply(
                Keys.join(source.chat().key(), source.message().id(), source.message().index()),
                scene == Scene.C2C ? 4 : 5);
        request.put("msg_seq", seq);
      } else request.remove("msg_type");
      return enqueue(source.chat(), request, deadline, "POST", null);
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public CompletionStage<ApiResponse> recall(ChatRef chat, String messageId) {
    try {
      check(chat);
      return enqueue(chat, Map.of(), 0, "DELETE", messageId);
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private CompletionStage<ApiResponse> enqueue(
      ChatRef chat, Map<String, Object> body, long deadline, String method, String messageId) {
    String root =
        switch (chat.scene()) {
          case GROUP -> "/v2/groups/";
          case C2C -> "/v2/users/";
          case CHANNEL -> "/channels/";
          case CHANNEL_DM -> "/dms/";
          case SYSTEM -> throw new IllegalArgumentException("System events have no message target");
        };
    String path =
        root
            + RouteCatalog.encode(chat.id())
            + "/messages"
            + (messageId == null ? "" : "/" + RouteCatalog.encode(messageId));
    Map<String, Object> request = new LinkedHashMap<>(body);
    if (chat.scene() == Scene.CHANNEL || chat.scene() == Scene.CHANNEL_DM)
      request.remove("msg_type");
    return queue.submit(
        owner, bot, method, path, Map.of(), request, null, null, Map.of(), deadline);
  }

  private void check(ChatRef chat) {
    if (!bot.equals(chat.bot()))
      throw new IllegalArgumentException("Cross-bot messaging is not allowed in this context");
  }
}
