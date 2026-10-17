package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.Set;

/** All received platform packets, independent of chat history and dispatch deduplication. */
public interface EventArchive {
  boolean enabled();

  Page<Record> search(Query query);

  record Query(BotId bot, Set<String> types, Instant from, Instant to, int offset, int limit) {
    public Query {
      types = types == null ? Set.of() : Set.copyOf(types);
    }
  }

  record Record(
      String receiptId,
      BotId bot,
      String transport,
      String eventType,
      int opcode,
      Long sequence,
      String platformEventId,
      Instant receivedAt,
      String rawPacket,
      Event event) {}
}
