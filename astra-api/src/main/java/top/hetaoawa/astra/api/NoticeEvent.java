package top.hetaoawa.astra.api;

public record NoticeEvent(EventData data, MemberChange memberChange, NoticePayload details)
    implements Event {
  public NoticeEvent(EventData data) {
    this(data, null, null);
  }

  public NoticeEvent(EventData data, MemberChange memberChange) {
    this(data, memberChange, null);
  }
}
