package top.hetaoawa.astra.core;

import java.util.List;

/** Atomic, persistent quota reservations. Uncertain HTTP outcomes retain their reservation. */
public interface QuotaStore {
  record Charge(String key, int limit, long expiresAt, String member, String rule) {
    public Charge(String key, int limit, long expiresAt, String member) {
      this(key, limit, expiresAt, member, key);
    }
  }

  record State(String value, long expiresAt) {}

  record Denied(String key, long expiresAt) {}

  /** Returns the exhausted key and reset time, or null after an atomic reservation. */
  Denied reserve(List<Charge> charges, long now);

  Denied check(List<Charge> charges, long now);

  State load(String key, long now);

  void save(String key, String value, long expiresAt);

  void cleanup(long now);
}
