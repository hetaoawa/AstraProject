package top.hetaoawa.astra.api;

public record MetaEvent(EventData data, GatewayDetails details) implements Event {
  public MetaEvent(EventData data) {
    this(data, null);
  }
}
