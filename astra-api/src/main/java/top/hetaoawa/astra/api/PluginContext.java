package top.hetaoawa.astra.api;

import java.util.Map;

public interface PluginContext {
  String pluginId();

  BotId bot();

  Messaging messaging();

  PlatformApi api();

  Directory directory();

  History history();

  default EventArchive eventArchive() {
    throw new EventArchiveDisabledException();
  }

  default HttpRoutes httpRoutes() {
    throw new IllegalStateException("HTTP routes unavailable");
  }

  Sessions sessions();

  Tasks tasks();

  Services services();

  Map<String, Object> configuration();

  default java.util.concurrent.CompletionStage<ApiResponse> reply(MessageEvent event, String text) {
    return messaging().replyText(event, text);
  }
}
