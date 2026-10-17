package top.hetaoawa.astra.api;

/** Opaque platform index. Temporary indexes are observation-scoped capabilities, not identities. */
public record MessageIndex(String value, Kind kind, String observedIn) {
  public enum Kind {
    STABLE,
    TEMPORARY,
    UNKNOWN
  }

  public MessageIndex {
    value = value == null ? "" : value;
    kind =
        value.startsWith("TMP_")
            ? Kind.TEMPORARY
            : value.startsWith("REFIDX_") ? Kind.STABLE : Kind.UNKNOWN;
    observedIn = observedIn == null ? "" : observedIn;
  }

  public static MessageIndex observed(String value, String eventKey) {
    return new MessageIndex(value, null, eventKey);
  }

  public boolean reusable() {
    return kind == Kind.STABLE;
  }
}
