package top.hetaoawa.astra.api;

public sealed interface Event
    permits MessageEvent, NoticeEvent, RequestEvent, MetaEvent, RawEvent, MessageRecallEvent {
  EventData data();

  default String key() {
    return data().key();
  }

  default ChatRef chat() {
    return data().chat();
  }

  default Identity actor() {
    return data().actor();
  }

  default BotId bot() {
    return data().bot();
  }
}
