package top.hetaoawa.astra.api;

public record MessageRecallEvent(
    EventData data,
    String messageId,
    String messageIndex,
    Message originalMessage,
    PlatformMember operator)
    implements Event {
  public MessageRecallEvent(EventData data, String messageId, String messageIndex) {
    this(data, messageId, messageIndex, null, null);
  }
}
