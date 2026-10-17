package top.hetaoawa.astra.core;

import top.hetaoawa.astra.api.BotId;

public interface GatewayStore {
  record Position(String session, long sequence) {}

  Position load(BotId bot, int shard);

  void save(BotId bot, int shard, Position position);
}
