package top.hetaoawa.astra.api;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface Messaging {
  /**
   * Group/C2C sends first reserve a usable received message ID, then fall back to active sending.
   */
  CompletionStage<ApiResponse> send(ChatRef chat, Map<String, Object> body);

  /**
   * Always replies to this source; an expired or exhausted source fails without choosing another.
   */
  CompletionStage<ApiResponse> reply(MessageEvent source, Map<String, Object> body);

  CompletionStage<ApiResponse> recall(ChatRef chat, String messageId);

  default CompletionStage<ApiResponse> send(ChatRef chat, MessagePayload body) {
    return send(chat, body.toMap());
  }

  default CompletionStage<ApiResponse> reply(MessageEvent source, MessagePayload body) {
    return reply(source, body.toMap());
  }

  default CompletionStage<ApiResponse> replyText(MessageEvent event, String text) {
    return reply(event, Map.of("content", text, "msg_type", 0));
  }
}
