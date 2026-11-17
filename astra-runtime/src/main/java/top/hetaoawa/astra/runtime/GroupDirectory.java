package top.hetaoawa.astra.runtime;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.JdbcDirectory;

/** Group lookups share the runtime outbound queue and its rate limits. */
final class GroupDirectory implements Directory {
  private static final Logger LOG = LoggerFactory.getLogger(GroupDirectory.class);
  private static final Duration MAX_AGE = Duration.ofHours(6);
  private static final Duration RETRY_DELAY = Duration.ofMinutes(5);
  private final JdbcDirectory cache;
  private final OutboundQueue outbound;
  private final List<BotId> bots;
  private final ConcurrentMap<Identity, CompletableFuture<Optional<Profile>>> inFlight =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Identity, Instant> retryAfter = new ConcurrentHashMap<>();

  GroupDirectory(JdbcDirectory cache, OutboundQueue outbound, List<BotId> bots) {
    this.cache = cache;
    this.outbound = outbound;
    this.bots = List.copyOf(bots);
  }

  public Optional<Profile> find(Identity id) {
    return cache.find(id);
  }

  public Optional<Profile> find(Identity id, boolean forceRefresh) {
    if (!forceRefresh) return find(id);
    if (!group(id))
      throw new IllegalArgumentException("Forced refresh requires a group_openid group identity");
    if (!bots.contains(id.bot())) throw new IllegalArgumentException("Unknown bot");
    return await(refresh(id, true));
  }

  void observe(Event event) {
    if (event instanceof MessageEvent && event.chat().scene() == Scene.GROUP)
      refreshIfNeeded(
          new Identity(event.bot(), EntityType.GROUP, "group_openid", event.chat().id()));
  }

  void refreshKnownGroups() {
    for (var bot : bots) {
      var groups = new ArrayList<Profile>();
      int offset = 0;
      Page<Profile> page;
      do {
        page = cache.search(bot, EntityType.GROUP, null, offset, 100);
        groups.addAll(page.items());
        offset += page.items().size();
      } while (page.hasMore());
      // Snapshot before refreshing: excluding dissolved rows must not shift page offsets.
      for (var profile : groups) {
        if (Thread.currentThread().isInterrupted()) return;
        if (group(profile.identity()) && !Boolean.FALSE.equals(profile.fields().get("bot_present")))
          refreshIfNeeded(profile.identity());
      }
    }
  }

  private static boolean group(Identity id) {
    return id.type() == EntityType.GROUP && id.kind().equals("group_openid");
  }

  private boolean due(Identity id) {
    var now = Instant.now();
    if (now.isBefore(retryAfter.getOrDefault(id, Instant.MIN))) return false;
    var updated = cache.find(id).map(p -> p.fields().get("group_info_updated_at")).orElse(null);
    return !(updated instanceof Number stamp)
        || !now.isBefore(Instant.ofEpochMilli(stamp.longValue()).plus(MAX_AGE));
  }

  private void refreshIfNeeded(Identity id) {
    try {
      await(refresh(id, false));
    } catch (RuntimeException error) {
      if (Thread.currentThread().isInterrupted()) throw error;
      LOG.warn("Group information refresh failed for {}; keeping cached data", id, error);
    }
  }

  private CompletableFuture<Optional<Profile>> refresh(Identity id, boolean force) {
    if (cache.isGroupDissolved(id)) return CompletableFuture.completedFuture(Optional.empty());
    if (!force && !due(id)) return CompletableFuture.completedFuture(cache.find(id));
    var result = new CompletableFuture<Optional<Profile>>();
    var existing = inFlight.putIfAbsent(id, result);
    if (existing != null) return existing;
    try {
      // Another request may have completed between the initial check and reservation.
      if (!force && !due(id)) {
        inFlight.remove(id, result);
        result.complete(cache.find(id));
        return result;
      }
      outbound
          .owned("astra:group-directory", id.bot())
          .call(
              QqEndpoints.V2_GROUPS_GROUP_OPENID_INFO_GET,
              Map.of("group_openid", id.value()),
              Map.of(),
              Map.of())
          .whenComplete(
              (response, error) -> {
                // ApiObservations has saved the response before this completion runs.
                if (error != null) failed(id, result, error);
                else {
                  try {
                    var profile = cache.find(id);
                    retryAfter.remove(id);
                    inFlight.remove(id, result);
                    result.complete(profile);
                  } catch (RuntimeException failure) {
                    failed(id, result, failure);
                  }
                }
              });
    } catch (RuntimeException error) {
      failed(id, result, error);
    }
    return result;
  }

  private void failed(Identity id, CompletableFuture<Optional<Profile>> result, Throwable error) {
    try {
      if (cache.isGroupDissolved(id)) {
        retryAfter.remove(id);
        inFlight.remove(id, result);
        result.complete(Optional.empty());
        return;
      }
    } catch (RuntimeException failure) {
      error = failure;
    }
    retryAfter.put(id, Instant.now().plus(RETRY_DELAY));
    inFlight.remove(id, result);
    result.completeExceptionally(error);
  }

  private static <T> T await(CompletableFuture<T> result) {
    try {
      return result.get();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Group information refresh interrupted", error);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof RuntimeException cause) throw cause;
      throw new IllegalStateException("Group information refresh failed", error.getCause());
    }
  }

  public Page<Profile> search(BotId bot, EntityType type, String name, int offset, int limit) {
    return cache.search(bot, type, name, offset, limit);
  }

  public Page<Profile> members(ChatRef chat, int offset, int limit) {
    return cache.members(chat, offset, limit);
  }

  public Page<MemberRelation> relationships(ChatRef chat, boolean inactive, int offset, int limit) {
    return cache.relationships(chat, inactive, offset, limit);
  }

  public Page<Profile> related(Identity id, int offset, int limit) {
    return cache.related(id, offset, limit);
  }

  public Map<String, Object> metadata(String owner, Identity id) {
    return cache.metadata(owner, id);
  }

  public void metadata(String owner, Identity id, Map<String, Object> values) {
    cache.metadata(owner, id, values);
  }
}
