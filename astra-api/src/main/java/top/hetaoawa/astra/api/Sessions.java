package top.hetaoawa.astra.api;

import java.time.Duration;
import java.util.function.Predicate;

public interface Sessions {
  Handle await(
      ChatRef chat,
      Identity user,
      Predicate<MessageEvent> filter,
      Duration timeout,
      ReplyHandler reply,
      Runnable expired);

  void cancel(ChatRef chat, Identity user);

  interface Handle {
    void cancel();
  }

  @FunctionalInterface
  interface ReplyHandler {
    java.util.concurrent.CompletionStage<HandlerResult> accept(
        MessageEvent reply, PluginContext context) throws Exception;
  }
}
