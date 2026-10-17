package top.hetaoawa.astra.example;

import java.time.*;
import java.util.concurrent.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.api.annotation.*;

public final class ExamplePlugin implements AstraPlugin {
  private static final System.Logger LOG = System.getLogger(ExamplePlugin.class.getName());

  public void onEnable(PluginContext context) {
    context
        .services()
        .registerPermission(
            new PermissionNode(
                "example.info", "Query observed profile information", context.pluginId()));
    String base = "/plugins/example/" + context.bot().environment() + "/" + context.bot().appId();
    context
        .httpRoutes()
        .register(
            "GET",
            base + "/status",
            request ->
                CompletableFuture.completedFuture(
                    HttpRoutes.Response.text(200, "astra-example: enabled")));
    context
        .httpRoutes()
        .register(
            "GET",
            base + "/echo/{value}",
            request ->
                CompletableFuture.completedFuture(
                    HttpRoutes.Response.text(200, request.pathVariables().get("value"))));
    context
        .httpRoutes()
        .register(
            "POST",
            base + "/echo",
            request ->
                CompletableFuture.completedFuture(HttpRoutes.Response.text(200, request.text())));
  }

  @OnCommand(
      value = "ping",
      aliases = {"p"})
  public CompletionStage<ApiResponse> ping(
      MessageEvent event, PluginContext context, CommandArgs arguments) {
    return context.reply(event, arguments.text().isBlank() ? "pong" : "pong " + arguments.text());
  }

  @OnCommand("info")
  public CompletionStage<ApiResponse> info(MessageEvent event, PluginContext context) {
    if (event.actor() == null) return context.reply(event, "本事件没有用户标识");
    var profile = context.directory().find(event.actor());
    return context.reply(
        event,
        profile
            .map(
                p ->
                    "资料："
                        + p.fields()
                        + "\n来源："
                        + p.source()
                        + "\n最后观察："
                        + p.lastSeen()
                        + "\n完整："
                        + p.complete())
            .orElse("暂无已观察的资料"));
  }

  @OnCommand("ask")
  public CompletionStage<ApiResponse> ask(MessageEvent event, PluginContext context) {
    if (event.actor() == null) return context.reply(event, "本事件没有用户标识");
    context
        .sessions()
        .await(
            event.chat(),
            event.actor(),
            reply -> !reply.message().text().startsWith("/"),
            Duration.ofSeconds(60),
            (reply, ctx) ->
                ctx.reply(reply, "收到：" + reply.message().text())
                    .thenApply(response -> HandlerResult.CONTINUE),
            () ->
                LOG.log(
                    System.Logger.Level.INFO,
                    "Conversation expired for bot " + context.bot().appId()));
    return context.reply(event, "请在 60 秒内回复；你的下一条普通消息将交给此会话。");
  }

  @OnCommand("history")
  public CompletionStage<ApiResponse> history(MessageEvent event, PluginContext context) {
    if (!context.history().enabled()) return context.reply(event, "消息历史未启用");
    var records =
        context
            .history()
            .search(
                new History.Query(
                    context.bot(),
                    event.chat(),
                    event.actor(),
                    null,
                    null,
                    null,
                    History.Direction.ANY,
                    0,
                    5));
    return context.reply(event, "最近记录数：" + records.items().size() + "（包含已撤回记录）");
  }

  @OnNotice(types = {"GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT"})
  public void groupNotice(NoticeEvent event) {
    LOG.log(System.Logger.Level.INFO, event.data().platformType() + " in " + event.chat().id());
  }

  @OnEvent
  public void unknown(Event event) {
    if (event instanceof RawEvent)
      LOG.log(
          System.Logger.Level.DEBUG,
          "Unknown platform event retained: " + event.data().platformType());
  }

  public void onError(HandlerFailure failure, PluginContext context) {
    LOG.log(
        System.Logger.Level.WARNING,
        "Handler failed for " + context.bot().appId() + ": " + failure.handlerId(),
        failure.cause());
  }
}
