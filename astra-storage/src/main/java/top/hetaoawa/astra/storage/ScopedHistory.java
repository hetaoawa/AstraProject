package top.hetaoawa.astra.storage;

import java.util.Optional;
import top.hetaoawa.astra.api.*;

public record ScopedHistory(History delegate, BotId bot) implements History {
  public boolean enabled() {
    return delegate.enabled();
  }

  private void check(BotId b) {
    if (!bot.equals(b)) throw new IllegalArgumentException("History bot scope mismatch");
  }

  public Optional<MessageRecord> find(ChatRef c, String id, String index) {
    check(c.bot());
    return delegate.find(c, id, index);
  }

  public Page<MessageRecord> search(Query q) {
    check(q.bot());
    return delegate.search(q);
  }

  public Optional<MessageRecord> findByIndex(ChatRef chat, String index) {
    check(chat.bot());
    return delegate.findByIndex(chat, index);
  }

  public Optional<Context> context(ContextQuery query) {
    check(query.chat().bot());
    return delegate.context(query);
  }
}
