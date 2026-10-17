package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface History {
  boolean enabled();

  Optional<MessageRecord> find(ChatRef chat, String messageId, String index);

  Optional<MessageRecord> findByIndex(ChatRef chat, String index);

  default Optional<MessageRecord> findByIndex(BotId bot, ChatRef chat, String index) {
    if (!bot.equals(chat.bot())) throw new IllegalArgumentException("History bot mismatch");
    return findByIndex(chat, index);
  }

  Optional<Context> context(ContextQuery query);

  Page<MessageRecord> search(Query query);

  record Query(
      BotId bot,
      ChatRef chat,
      Identity actor,
      Instant from,
      Instant to,
      Boolean recalled,
      Direction direction,
      int offset,
      int limit) {
    public Query {
      direction = direction == null ? Direction.ANY : direction;
    }

    public Query(
        BotId bot,
        ChatRef chat,
        Identity actor,
        Instant from,
        Instant to,
        Boolean recalled,
        int offset,
        int limit) {
      this(bot, chat, actor, from, to, recalled, Direction.ANY, offset, limit);
    }
  }

  enum Direction {
    INBOUND,
    OUTBOUND,
    ANY
  }

  enum RecallSource {
    API_SUCCESS,
    PLATFORM_NOTICE
  }

  record ContextQuery(
      ChatRef chat,
      String messageId,
      String index,
      Identity actor,
      int before,
      int after,
      Direction direction) {
    public ContextQuery {
      if (before < 0 || after < 0 || before > 500 || after > 500)
        throw new IllegalArgumentException("Context counts must be between 0 and 500");
      if (actor != null && !chat.bot().equals(actor.bot()))
        throw new IllegalArgumentException("Context actor bot mismatch");
      direction = direction == null ? Direction.ANY : direction;
    }
  }

  /** Before and after are in chronological order; filtering does not remove the anchor. */
  record Context(MessageRecord anchor, List<MessageRecord> before, List<MessageRecord> after) {
    public Context {
      before = List.copyOf(before);
      after = List.copyOf(after);
    }
  }

  record MessageRecord(
      ChatRef chat,
      Identity actor,
      Message message,
      Instant occurredAt,
      boolean outgoing,
      boolean recalled,
      Instant recalledAt,
      String processingState,
      String recordKey,
      Set<RecallSource> recallSources) {
    public MessageRecord {
      recallSources = recallSources == null ? Set.of() : Set.copyOf(recallSources);
    }
  }
}
