package top.hetaoawa.astra.core;

public interface OutboundStore {
  record Reply(String messageId, int sequence, long expiresAt) {}

  /** Repeated observations must not replenish a message's remaining replies or extend its life. */
  void rememberReply(String chatKey, String messageId, long expiresAt, int maximum);

  /** Atomically reserves a reply. A null messageId selects the earliest expiring usable ID. */
  Reply claimReply(String chatKey, String messageId, long now);

  void reserve(String id, String bot, String owner);

  boolean start(String id);

  void finish(String id, String state, String result);

  void cancel(String bot, String owner);

  void recover(boolean durable);

  int nextReply(String key, int maximum);
}
