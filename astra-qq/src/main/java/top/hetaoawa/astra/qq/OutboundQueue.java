package top.hetaoawa.astra.qq;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;

/** Ordered lanes per chat/operation; waiting quotas never occupy a bot's HTTP permits. */
public final class OutboundQueue implements AutoCloseable {
  public record Job(
      String id,
      BotId bot,
      String owner,
      String method,
      String path,
      Map<String, Object> query,
      Map<String, Object> body,
      String uploadUrl,
      String bytes,
      Map<String, String> headers,
      Map<String, UploadFile> files,
      long expiresAt) {}

  private final EventQueue queue;
  private final OutboundStore store;
  private final Map<BotId, DirectQqApi> apis;

  private record Work(EventQueue.Delivery delivery, Job job) {}

  private final ConcurrentMap<String, ConcurrentLinkedQueue<Work>> lanes =
      new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ConcurrentMap<String, CompletableFuture<ApiResponse>> results =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Job> pending = new ConcurrentHashMap<>();
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  public interface Observer {
    default void before(Job job) {}

    void after(Job job, ApiResponse response, Throwable failure);
  }

  private final Observer observer;

  public OutboundQueue(
      EventQueue queue,
      OutboundStore store,
      Map<BotId, DirectQqApi> apis,
      Map<BotId, Double> rates,
      BiConsumer<Job, ApiResponse> observer) {
    this(
        queue,
        store,
        apis,
        rates,
        new Observer() {
          public void after(Job job, ApiResponse response, Throwable error) {
            if (error == null) observer.accept(job, response);
          }
        });
  }

  public OutboundQueue(
      EventQueue queue,
      OutboundStore store,
      Map<BotId, DirectQqApi> apis,
      Map<BotId, Double> rates,
      Observer observer) {
    this.queue = queue;
    this.store = store;
    this.apis = Map.copyOf(apis);
    this.observer = observer;
    store.recover(queue.backend().equals("redis"));
    apis.keySet().forEach(bot -> workers.submit(() -> consume(bot)));
  }

  public OutboundQueue(
      EventQueue queue, OutboundStore store, Map<BotId, DirectQqApi> apis, Observer observer) {
    this(queue, store, apis, Map.of(), observer);
  }

  public PlatformApi owned(String owner, BotId bot) {
    var direct = apis.get(bot);
    return new PlatformApi() {
      public Map<String, ApiOperation> operations() {
        return direct.operations();
      }

      public CompletionStage<ApiResponse> call(
          String operation,
          Map<String, String> path,
          Map<String, Object> query,
          Map<String, Object> body) {
        try {
          var op = direct.operations().get(operation);
          if (op == null) throw new IllegalArgumentException("Unknown operation: " + operation);
          return raw(op.method(), RouteCatalog.expand(op.pathTemplate(), path), query, body);
        } catch (Throwable e) {
          return CompletableFuture.failedFuture(e);
        }
      }

      public CompletionStage<ApiResponse> raw(
          String method, String path, Map<String, Object> query, Map<String, Object> body) {
        return submit(owner, bot, method, path, query, body, null, null, Map.of(), 0);
      }

      public CompletionStage<ApiResponse> uploadPart(
          String url, byte[] bytes, Map<String, String> headers) {
        return submit(
            owner,
            bot,
            "PUT",
            "",
            Map.of(),
            Map.of(),
            url,
            Base64.getEncoder().encodeToString(bytes),
            headers,
            0);
      }

      public CompletionStage<ApiResponse> multipart(
          String method, String path, Map<String, String> fields, Map<String, UploadFile> files) {
        return submitFiles(
            owner,
            bot,
            method,
            path,
            Map.of(),
            new LinkedHashMap<>(fields),
            null,
            null,
            Map.of(),
            files,
            0);
      }
    };
  }

  public CompletionStage<ApiResponse> submit(
      String owner,
      BotId bot,
      String method,
      String path,
      Map<String, Object> query,
      Map<String, Object> body,
      String upload,
      String bytes,
      Map<String, String> headers,
      long expiresAt) {
    return submitFiles(
        owner, bot, method, path, query, body, upload, bytes, headers, Map.of(), expiresAt);
  }

  private CompletionStage<ApiResponse> submitFiles(
      String owner,
      BotId bot,
      String method,
      String path,
      Map<String, Object> query,
      Map<String, Object> body,
      String upload,
      String bytes,
      Map<String, String> headers,
      Map<String, UploadFile> files,
      long expiresAt) {
    String id = UUID.randomUUID().toString();
    var future = new CompletableFuture<ApiResponse>();
    try {
      var job =
          new Job(
              id,
              bot,
              owner,
              method,
              path,
              Collections.unmodifiableMap(new LinkedHashMap<>(query)),
              Collections.unmodifiableMap(new LinkedHashMap<>(body)),
              upload,
              bytes,
              Map.copyOf(headers),
              Map.copyOf(files),
              expiresAt);
      String payload = QqJson.write(job);
      if (payload.length() > 16 * 1024 * 1024)
        throw new IllegalArgumentException(
            "Outbound request exceeds 16 MiB; upload media in smaller parts");
      store.reserve(id, bot.key(), owner);
      observer.before(job);
      results.put(id, future);
      pending.put(id, job);
      if (!running.get() || !queue.offer(bot, payload)) {
        store.finish(id, "REJECTED", "Queue full or unavailable");
        results.remove(id);
        pending.remove(id);
        var error =
            new PlatformException(
                "Outbound queue full or unavailable",
                0,
                "QUEUE_UNAVAILABLE",
                "",
                !queue.backend().equals("redis"),
                null);
        observer.after(job, null, error);
        throw error;
      }
    } catch (Throwable e) {
      results.remove(id);
      pending.remove(id);
      future.completeExceptionally(e);
    }
    return future;
  }

  private void consume(BotId bot) {
    while (running.get()) {
      try {
        var delivery = queue.poll(bot, Duration.ofSeconds(1));
        if (delivery == null) continue;
        Job job = QqJson.MAPPER.readValue(delivery.payload(), Job.class);
        var limits = apis.get(bot).limits();
        String key =
            limits == null
                ? Keys.join(bot.key(), job.method(), job.path())
                : limits.lane(job.method(), job.path(), job.body(), job.uploadUrl() != null);
        lanes.compute(
            key,
            (k, lane) -> {
              boolean create = lane == null;
              if (create) lane = new ConcurrentLinkedQueue<>();
              lane.add(new Work(delivery, job));
              if (create) workers.submit(() -> drainLane(k));
              return lane;
            });
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable e) {
        org.slf4j.LoggerFactory.getLogger(OutboundQueue.class)
            .warn("Outbound backend unavailable; waiting for recovery");
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void drainLane(String key) {
    while (running.get()) {
      AtomicReference<Work> current = new AtomicReference<>();
      lanes.computeIfPresent(
          key,
          (k, lane) -> {
            current.set(lane.poll());
            return current.get() == null ? null : lane;
          });
      Work work = current.get();
      if (work == null) return;
      try {
        execute(work);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void execute(Work work) throws InterruptedException {
    Job job = work.job();
    ApiResponse response = null;
    Throwable error = null;
    try {
      while (running.get() && !queue.healthy()) Thread.sleep(250);
      OutboundLimits.Guard before =
          new OutboundLimits.Guard() {
            public void check() {
              if (!running.get()) throw new CancellationException("Runtime stopped");
              if (cancelled.contains(job.id())) throw new CancellationException("Plugin disabled");
              if (job.expiresAt() > 0 && System.currentTimeMillis() >= job.expiresAt())
                throw new PlatformException(
                    "Passive reply expired before transmission",
                    0,
                    "REPLY_EXPIRED",
                    "",
                    true,
                    null);
            }

            public boolean ready() {
              return queue.healthy();
            }

            public void run() {
              check();
              // Waiting jobs remain PENDING. Only an admitted HTTP call becomes STARTED.
              if (!store.start(job.id()))
                throw new PlatformException(
                    "Outbound request cancelled or interrupted; not replayed",
                    0,
                    "NOT_REPLAYED",
                    "",
                    true,
                    null);
            }
          };
      before.check();
      var api = apis.get(job.bot());
      if (job.uploadUrl() != null)
        response =
            api.uploadPart(
                    job.uploadUrl(), Base64.getDecoder().decode(job.bytes()), job.headers(), before)
                .toCompletableFuture()
                .get();
      else if (!job.files().isEmpty()) {
        Map<String, String> fields = new LinkedHashMap<>();
        job.body().forEach((k, v) -> fields.put(k, String.valueOf(v)));
        response =
            api.multipart(job.method(), job.path(), fields, job.files(), before)
                .toCompletableFuture()
                .get();
      } else response = api.execute(job.method(), job.path(), job.query(), job.body(), before);
    } catch (Throwable e) {
      error = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    }
    if (!running.get()) return;
    while (running.get()) {
      try {
        store.finish(
            job.id(),
            error == null ? "SUCCESS" : "FAILED",
            error == null ? QqJson.write(response) : error.toString());
        observer.after(job, response, error);
        queue.acknowledge(work.delivery());
        break;
      } catch (Throwable e) {
        Thread.sleep(1000);
      }
    }
    var future = results.remove(job.id());
    pending.remove(job.id());
    cancelled.remove(job.id());
    if (future != null) {
      if (error == null) future.complete(response);
      else future.completeExceptionally(error);
    }
  }

  public void cancel(String owner, BotId bot) {
    store.cancel(bot.key(), owner);
    pending.forEach(
        (id, job) -> {
          if (job.owner().equals(owner) && job.bot().equals(bot)) {
            cancelled.add(id);
            var f = results.remove(id);
            pending.remove(id);
            if (f != null) f.completeExceptionally(new CancellationException("Plugin disabled"));
          }
        });
  }

  public String backend() {
    return queue.backend();
  }

  public boolean healthy() {
    return queue.healthy();
  }

  public void close() {
    running.set(false);
    workers.shutdownNow();
    try {
      workers.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    results
        .values()
        .forEach(f -> f.completeExceptionally(new CancellationException("Runtime stopped")));
    results.clear();
    pending.clear();
    lanes.clear();
    cancelled.clear();
    queue.close();
  }
}
