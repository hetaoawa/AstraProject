package top.hetaoawa.astra.runtime;

import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import top.hetaoawa.astra.api.*;
import top.hetaoawa.astra.core.*;
import top.hetaoawa.astra.qq.*;
import top.hetaoawa.astra.storage.*;

@Component
public final class AstraRuntime implements DisposableBean {
  private static final Logger LOG = LoggerFactory.getLogger(AstraRuntime.class);
  private final AstraConfig config;
  private final QqEventCodec codec = new QqEventCodec();
  private final Map<BotId, AstraConfig.Bot> bots = new LinkedHashMap<>();
  private final Map<BotId, DirectQqApi> apis = new LinkedHashMap<>();
  private final Map<BotId, ServiceRegistry> services = new HashMap<>();
  private final List<QqGateway> gateways = new ArrayList<>();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private FileChannel lockChannel;
  private FileLock fileLock;
  private JdbcDatabase db;
  private JdbcRuntimeLease lease;
  private JdbcDirectory directory;
  private GroupDirectory groupDirectory;
  private JdbcHistory history;
  private JdbcEventArchive archive;
  private JdbcLedger ledger;
  private JdbcOutboundStore outboundStore;
  private JdbcQuotaStore quotaStore;
  private QueueFactory.Pair queues;
  private EventQueue inbound;
  private OutboundQueue outbound;
  private EventPump pump;
  private SessionManager sessions;
  private TaskManager tasks;
  private HandlerRegistry registry;
  private ContextFactory contexts;
  private final PluginRouteTable routes = new PluginRouteTable();
  private PluginHost plugins;
  private ScheduledExecutorService maintenance;

  public AstraRuntime(AstraConfig config) {
    this.config = config;
    try {
      start();
    } catch (Throwable e) {
      destroy();
      throw new IllegalStateException("Astra startup failed", e);
    }
  }

  private void start() throws Exception {
    if (config.history().retentionDays() < 0)
      throw new IllegalArgumentException("History retention-days must be >= 0 (0 means unlimited)");
    if (config.eventArchive().retentionDays() < 0)
      throw new IllegalArgumentException("Archive retention-days must be nonnegative");
    for (var bot : config.bots()) {
      var id = new BotId(bot.appId(), bot.environment());
      if (bots.putIfAbsent(id, bot) != null)
        throw new IllegalArgumentException("Duplicate bot " + id);
    }
    Path lock = Path.of(config.lockFile()).toAbsolutePath();
    Files.createDirectories(lock.getParent());
    lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    fileLock = lockChannel.tryLock();
    if (fileLock == null)
      throw new IllegalStateException("Another Astra process owns this runtime directory");
    var database = config.database();
    if (database.dialect().equals("sqlite")
        && database.url().startsWith("jdbc:sqlite:")
        && !database.url().contains(":memory:")) {
      Path file = Path.of(database.url().substring("jdbc:sqlite:".length())).toAbsolutePath();
      Files.createDirectories(file.getParent());
    }
    db =
        new JdbcDatabase(
            database.dialect(), database.url(), database.username(), database.password());
    lease = new JdbcRuntimeLease(db);
    var policies =
        bots.entrySet().stream()
            .map(
                e ->
                    new BotPolicy(
                        e.getKey(),
                        e.getValue().concurrency(),
                        e.getValue().queueCapacity(),
                        e.getValue().unionScope(),
                        e.getValue().unionEnabled()))
            .toList();
    var outPolicies =
        bots.entrySet().stream()
            .map(
                e ->
                    new BotPolicy(
                        e.getKey(),
                        1,
                        e.getValue().outboundCapacity(),
                        e.getValue().unionScope(),
                        e.getValue().unionEnabled()))
            .toList();
    queues =
        QueueFactory.select(
            config.redis().enabled(),
            config.redis().uri(),
            config.redis().namespace(),
            policies,
            outPolicies);
    inbound = queues.inbound();
    directory = new JdbcDirectory(db, policies);
    history = new JdbcHistory(db, config.history().enabled());
    archive = new JdbcEventArchive(db, codec, config.eventArchive().enabled());
    ledger = new JdbcLedger(db);
    ledger.recoverInterrupted();
    outboundStore = new JdbcOutboundStore(db, lease::valid);
    var apiRoutes = new RouteCatalog();
    quotaStore = new JdbcQuotaStore(db);
    bots.forEach(
        (id, bot) -> {
          var limits = new OutboundLimits(id, apiRoutes, bot.rateLimits().policy(), quotaStore);
          apis.put(id, new DirectQqApi(credentials(id), apiRoutes, limits));
          services.put(id, new ServiceRegistry());
        });
    var observer = new ApiObservations(directory, history);
    outbound = new OutboundQueue(queues.outbound(), outboundStore, apis, observer);
    groupDirectory = new GroupDirectory(directory, outbound, List.copyOf(bots.keySet()));
    registry = new HandlerRegistry();
    sessions = new SessionManager();
    tasks = new TaskManager();
    contexts =
        (owner, bot) ->
            new RuntimeContext(
                owner,
                bot,
                registry,
                outbound,
                outboundStore,
                groupDirectory,
                history,
                sessions,
                tasks,
                services.get(bot),
                config.plugins().configuration().getOrDefault(owner, Map.of()),
                archive,
                routes);
    Path pluginPath = Path.of(config.plugins().directory());
    Files.createDirectories(pluginPath);
    plugins =
        new PluginHost(
            new AstraPluginManager(pluginPath),
            registry,
            contexts,
            List.copyOf(bots.keySet()),
            sessions,
            tasks,
            outbound,
            db,
            services,
            config.plugins().enabledByDefault());
    var dispatcher =
        new Dispatcher(
            registry,
            ledger,
            event -> {
              if (!lease.valid())
                throw new IllegalStateException("Runtime database lease lost; restart required");
              directory.observe(event);
              outbound.observe(event);
              apis.get(event.data().bot()).limits().observe(event);
              history.observe(event);
              groupDirectory.observe(event);
            },
            sessions,
            contexts,
            () -> lease.valid() && inbound.healthy() && outbound.healthy());
    pump = new EventPump(inbound, codec, dispatcher, policies);
    bots.forEach(
        (id, bot) -> {
          if (bot.transport().equals("WEBSOCKET"))
            gateways.add(
                new QqGateway(
                    credentials(id),
                    apis.get(id),
                    new JdbcGatewayStore(db),
                    codec,
                    this::receive,
                    bot.intents(),
                    bot.shards(),
                    inbound.backend().equals("redis"),
                    packet -> capturePacket(id, "WEBSOCKET", packet)));
        });
    maintenance = Executors.newScheduledThreadPool(2);
    maintenance.scheduleWithFixedDelay(
        () -> {
          try {
            if (lease.valid()) groupDirectory.refreshKnownGroups();
          } catch (RuntimeException error) {
            LOG.warn(
                "Group information maintenance failed; will retry at the next interval", error);
          }
        },
        1,
        10,
        TimeUnit.MINUTES);
    maintenance.scheduleWithFixedDelay(
        () -> {
          try {
            history.cleanup(Duration.ofDays(config.history().retentionDays()));
            outboundStore.cleanup(Instant.now().minus(Duration.ofDays(30)).toEpochMilli());
            quotaStore.cleanup(System.currentTimeMillis());
            ledger.cleanup(Instant.now().minus(Duration.ofDays(30)).toEpochMilli());
            archive.cleanup(Duration.ofDays(config.eventArchive().retentionDays()));
          } catch (Throwable e) {
            LOG.warn("Maintenance failed; will run at the next interval", e);
          }
        },
        1,
        60,
        TimeUnit.MINUTES);
    if (config.console()) Thread.ofVirtual().name("astra-console").start(this::console);
    LOG.info(
        "Astra started with {} bots; fixed queue backend {}; message history {}",
        bots.size(),
        inbound.backend(),
        history.enabled() ? "enabled" : "disabled");
  }

  public BotCredentials credentials(BotId id) {
    var b = bots.get(id);
    if (b == null) throw new IllegalArgumentException("Unknown bot");
    return new BotCredentials(id, b.secret(), URI.create(b.apiBase()), b.requestTimeout());
  }

  public Optional<AstraConfig.Bot> bot(BotId id) {
    return Optional.ofNullable(bots.get(id));
  }

  public boolean receive(Event event) {
    if (stopped.get() || lease == null || !lease.valid() || pump == null) return false;
    outbound.observe(event);
    return pump.accept(event);
  }

  public Event normalize(BotId id, Map<String, Object> payload) {
    return codec.normalize(id, payload);
  }

  public void capturePacket(BotId bot, String transport, String packet) {
    if (!archive.enabled()) return;
    if (stopped.get() || lease == null || !lease.valid())
      throw new IllegalStateException("Archive unavailable");
    var received = Instant.now();
    Map<String, Object> envelope;
    Event event;
    try {
      envelope = QqJson.map(packet);
    } catch (IllegalArgumentException error) {
      envelope = Map.of("t", "MALFORMED_PACKET");
    }
    try {
      event = codec.normalize(bot, envelope);
    } catch (RuntimeException error) {
      var raw = new LinkedHashMap<>(envelope);
      raw.put("normalization_error", error.getClass().getSimpleName() + ": " + error.getMessage());
      event =
          new RawEvent(
              new EventData(
                  UUID.randomUUID().toString(),
                  QqJson.string(envelope.get("t")),
                  QqJson.string(envelope.get("id")),
                  new ChatRef(bot, Scene.SYSTEM, "bot", ""),
                  null,
                  received,
                  raw));
    }
    archive.append(bot, transport, packet, envelope, event, received);
  }

  PluginRouteTable routes() {
    return routes;
  }

  HandlerRegistry registry() {
    return registry;
  }

  void routeError(PluginRouteTable.Route route, Throwable error) {
    registry
        .plugin(route.owner())
        .ifPresent(
            plugin -> {
              try {
                plugin.onError(
                    new HandlerFailure(
                        route.owner(),
                        "HTTP " + route.method() + " " + route.path(),
                        null,
                        error,
                        false),
                    contexts.create(route.owner(), route.bot()));
              } catch (Throwable callback) {
                LOG.warn("Plugin HTTP error callback failed", callback);
              }
            });
    LOG.warn("Plugin HTTP handler failed: {} {}", route.owner(), route.path(), error);
  }

  public Map<String, Object> status() {
    boolean healthy =
        !stopped.get()
            && lease != null
            && lease.valid()
            && inbound != null
            && inbound.healthy()
            && (pump == null || pump.healthy())
            && outbound != null
            && outbound.healthy()
            && gateways.stream().allMatch(QqGateway::healthy);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("status", healthy ? "UP" : "DOWN");
    data.put("queue", inbound == null ? "initializing" : inbound.backend());
    data.put("bots", bots.size());
    data.put("inFlightChats", pump == null ? 0 : pump.inFlight());
    data.put("historyEnabled", history != null && history.enabled());
    data.put("scheduledTasks", tasks == null ? 0 : tasks.count());
    return data;
  }

  private void console() {
    var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
    try {
      String line;
      while (!stopped.get() && (line = input.readLine()) != null) {
        try {
          String[] p = line.strip().split("\\s+");
          if (p[0].equals("status")) LOG.info("{}", status());
          else if (p.length == 2 && p[0].equals("plugins") && p[1].equals("list"))
            LOG.info("{}", plugins.list());
          else if ((p.length == 3 || p.length == 5)
              && p[0].equals("plugins")
              && Set.of("enable", "disable").contains(p[1]))
            plugins.setEnabled(
                p[2], p.length == 5 ? new BotId(p[3], p[4]) : null, p[1].equals("enable"));
          else
            LOG.info(
                "Commands: status | plugins list | plugins enable|disable <id> [<app-id>"
                    + " <environment>]");
        } catch (Throwable e) {
          LOG.warn("Console command rejected: {}", e.getMessage());
        }
      }
    } catch (java.io.IOException e) {
      LOG.debug("Console closed");
    }
  }

  public void destroy() {
    if (!stopped.compareAndSet(false, true)) return;
    gateways.forEach(QqGateway::close);
    if (maintenance != null) maintenance.shutdownNow();
    if (pump != null) pump.close();
    else if (inbound != null) inbound.close();
    if (outbound != null) outbound.close();
    if (plugins != null) plugins.close();
    if (queues != null) queues.close();
    if (tasks != null) tasks.close();
    if (sessions != null) sessions.close();
    if (lease != null)
      try {
        lease.close();
      } catch (Throwable e) {
        LOG.warn("Runtime lease release failed");
      }
    if (db != null) db.close();
    try {
      if (fileLock != null) fileLock.release();
      if (lockChannel != null) lockChannel.close();
    } catch (java.io.IOException e) {
      LOG.warn("Runtime file lock release failed");
    }
  }
}
