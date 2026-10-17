package top.hetaoawa.astra.api;

public final class HistoryDisabledException extends IllegalStateException {
  public HistoryDisabledException() {
    super("Message history is disabled");
  }
}
