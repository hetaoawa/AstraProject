package top.hetaoawa.astra.storage;

import java.util.*;
import top.hetaoawa.astra.api.*;

public record ScopedDirectory(Directory delegate, String owner, BotId bot) implements Directory {
  private void check(BotId b) {
    if (!bot.equals(b))
      throw new IllegalArgumentException(
          "Bot scope mismatch; use related() for configured cross-bot lookup");
  }

  public Optional<Profile> find(Identity id) {
    check(id.bot());
    return delegate.find(id);
  }

  public Page<Profile> search(BotId b, EntityType t, String name, int offset, int limit) {
    check(b);
    return delegate.search(b, t, name, offset, limit);
  }

  public Page<Profile> members(ChatRef c, int offset, int limit) {
    check(c.bot());
    return delegate.members(c, offset, limit);
  }

  public Page<MemberRelation> relationships(ChatRef c, boolean inactive, int offset, int limit) {
    check(c.bot());
    return delegate.relationships(c, inactive, offset, limit);
  }

  public Page<Profile> related(Identity id, int offset, int limit) {
    check(id.bot());
    return delegate.related(id, offset, limit);
  }

  private void owner(String p) {
    if (!owner.equals(p)) throw new IllegalArgumentException("Metadata owner mismatch");
  }

  public Map<String, Object> metadata(String p, Identity id) {
    owner(p);
    check(id.bot());
    return delegate.metadata(owner, id);
  }

  public void metadata(String p, Identity id, Map<String, Object> values) {
    owner(p);
    check(id.bot());
    delegate.metadata(owner, id, values);
  }
}
