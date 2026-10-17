package top.hetaoawa.astra.api;

public final class EventArchiveDisabledException extends IllegalStateException {
  public EventArchiveDisabledException() {
    super("Platform event archive is disabled");
  }
}
