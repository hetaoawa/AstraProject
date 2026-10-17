package top.hetaoawa.astra.api;

public record RequestEvent(EventData data, GroupJoinRequest request) implements Event {
  public RequestEvent(EventData data) {
    this(data, null);
  }
}
