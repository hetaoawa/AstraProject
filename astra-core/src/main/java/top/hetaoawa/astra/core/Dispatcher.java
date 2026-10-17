package top.hetaoawa.astra.core;

import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import top.hetaoawa.astra.api.*;

public final class Dispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);
  private final HandlerRegistry registry;
  private final ProcessingStore store;
  private final ObservationStore observations;
  private final SessionManager sessions;
  private final ContextFactory contexts;
  private final java.util.function.BooleanSupplier available;

  public Dispatcher(
      HandlerRegistry registry,
      ProcessingStore store,
      ObservationStore observations,
      SessionManager sessions,
      ContextFactory contexts) {
    this(registry, store, observations, sessions, contexts, () -> true);
  }

  public Dispatcher(
      HandlerRegistry registry,
      ProcessingStore store,
      ObservationStore observations,
      SessionManager sessions,
      ContextFactory contexts,
      java.util.function.BooleanSupplier available) {
    this.registry = registry;
    this.store = store;
    this.observations = observations;
    this.sessions = sessions;
    this.contexts = contexts;
    this.available = available;
  }

  private void awaitInfrastructure() {
    while (!available.getAsBoolean())
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Runtime stopping", e);
      }
  }

  public void dispatch(Event event) {
    awaitInfrastructure();
    if (store.completed(event.key())) return;
    observations.observe(event);
    Optional<SessionManager.Match> session =
        event instanceof MessageEvent m
            ? sessions.matching(m, s -> registry.enabled(s.owner, event.bot()))
            : Optional.empty();
    List<ProcessingStore.Plan> proposed =
        session
            .map(s -> List.of(new ProcessingStore.Plan(s.owner, s.id)))
            .orElseGet(
                () ->
                    registry.matching(event).stream()
                        .map(h -> new ProcessingStore.Plan(h.pluginId(), h.id()))
                        .toList());
    List<ProcessingStore.Plan> plans = store.prepare(event, proposed);
    boolean blocked = store.blocked(event.key()), failed = false;
    for (var plan : plans) {
      awaitInfrastructure();
      if (!store.start(event.key(), plan.handlerId())) continue;
      if (blocked) {
        store.finishHandler(event.key(), plan.handlerId(), "SKIPPED_BLOCKED", "");
        continue;
      }
      try (var lease = registry.acquire(plan.pluginId(), event.bot())) {
        if (lease == null) {
          store.finishHandler(event.key(), plan.handlerId(), "SKIPPED_UNAVAILABLE", "");
          continue;
        }
        if (plan.handlerId().startsWith("session.")) {
          blocked = true;
          var match = session.filter(s -> s.id.equals(plan.handlerId())).orElse(null);
          if (match == null || !(event instanceof MessageEvent m) || !sessions.claim(m, match)) {
            store.finishHandler(
                event.key(),
                plan.handlerId(),
                "INTERRUPTED",
                "Session continuation no longer available");
            continue;
          }
          try {
            match.invoke(m).toCompletableFuture().get();
            store.finishHandler(event.key(), plan.handlerId(), "SUCCESS_BLOCK", "");
          } catch (Throwable error) {
            failed = true;
            failure(plan, event, error, match.context);
          }
          continue;
        }
        var found = registry.find(plan.handlerId(), event.bot());
        if (found.isEmpty()) {
          store.finishHandler(event.key(), plan.handlerId(), "SKIPPED_UNAVAILABLE", "");
          continue;
        }
        var handler = found.get();
        var context = contexts.create(handler.pluginId(), event.bot());
        try {
          var result = handler.invoke(event, context).toCompletableFuture().get();
          blocked = result == HandlerResult.BLOCK || handler.block();
          store.finishHandler(event.key(), handler.id(), blocked ? "SUCCESS_BLOCK" : "SUCCESS", "");
        } catch (Throwable error) {
          failed = true;
          failure(plan, event, error, context);
        }
      }
    }
    store.finishEvent(event, failed ? "FAILED_HANDLERS" : "DONE");
  }

  private void failure(
      ProcessingStore.Plan plan, Event event, Throwable error, PluginContext context) {
    Throwable cause =
        error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    store.finishHandler(event.key(), plan.handlerId(), "FAILED", cause.toString());
    LOG.warn("Plugin invocation failed; no retry: {}", plan.handlerId(), cause);
    registry
        .plugin(plan.pluginId())
        .ifPresent(
            plugin -> {
              try {
                plugin.onError(
                    new HandlerFailure(
                        plan.pluginId(),
                        plan.handlerId(),
                        event,
                        cause,
                        error instanceof InterruptedException),
                    context);
              } catch (Throwable callback) {
                LOG.warn("Plugin error callback failed", callback);
              }
            });
    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
  }
}
