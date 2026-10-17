package top.hetaoawa.astra.api;

import java.util.Map;
import java.util.Optional;

public interface Directory {
  Optional<Profile> find(Identity identity);

  Page<Profile> search(BotId bot, EntityType type, String name, int offset, int limit);

  Page<Profile> members(ChatRef chat, int offset, int limit);

  Page<MemberRelation> relationships(ChatRef chat, boolean includeInactive, int offset, int limit);

  Page<Profile> related(Identity identity, int offset, int limit);

  Map<String, Object> metadata(String pluginId, Identity identity);

  void metadata(String pluginId, Identity identity, Map<String, Object> values);
}
