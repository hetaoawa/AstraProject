package top.hetaoawa.astra.core;

import java.time.Duration;
import top.hetaoawa.astra.api.BotId;

public interface EventQueue extends AutoCloseable {
  boolean offer(BotId bot, String payload);

  Delivery poll(BotId bot, Duration timeout) throws InterruptedException;

  void acknowledge(Delivery delivery);

  boolean healthy();

  String backend();

  record Delivery(BotId bot, String id, String payload) {}

  @Override
  void close();
}
