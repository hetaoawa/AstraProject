package top.hetaoawa.astra.core;

public interface OutboundStore {
  void reserve(String id, String bot, String owner);

  boolean start(String id);

  void finish(String id, String state, String result);

  void cancel(String bot, String owner);

  void recover(boolean durable);

  int nextReply(String key, int maximum);
}
