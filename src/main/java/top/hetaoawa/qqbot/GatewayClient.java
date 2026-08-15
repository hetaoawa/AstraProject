package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class GatewayClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QQBot bot;
    private final BotConfig config;
    private final HttpApiClient api;
    private final HttpClient httpClient;
    private final AstraLogger logger;
    private final Executor httpExecutor;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "qqbot-gateway-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final Object lock = new Object();

    private volatile WebSocket webSocket;
    private volatile boolean stopped;
    private volatile Long sequence;
    private volatile String sessionId;
    private volatile long reconnectDelayMillis;
    private volatile ScheduledFuture<?> heartbeatTask;
    private CompletableFuture<Void> readyFuture = new CompletableFuture<>();

    GatewayClient(QQBot bot, BotConfig config, HttpApiClient api, HttpClient httpClient,
                  AstraLogger logger, Executor httpExecutor) {
        this.bot = bot;
        this.config = config;
        this.api = api;
        this.httpClient = httpClient;
        this.logger = logger;
        this.httpExecutor = httpExecutor;
        this.reconnectDelayMillis = config.reconnectInitialDelay().toMillis();
    }

    CompletableFuture<Void> start() {
        synchronized (lock) {
            if (stopped) {
                throw new IllegalStateException("gateway client is closed");
            }
            if (!readyFuture.isDone()) {
                logger.info("GATEWAY", "starting shard=" + config.shardId() + "/" + config.shardCount());
                connectAttempt();
            }
            return readyFuture;
        }
    }

    private void connectAttempt() {
        if (stopped || !connecting.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                logger.debug("GATEWAY", "requesting gateway URL");
                JsonNode gateway = api.get("/gateway/bot");
                String url = gateway.path("url").asText();
                if (url.isBlank()) {
                    throw new IllegalStateException("QQ Bot gateway response did not contain url");
                }
                WebSocket socket = httpClient.newWebSocketBuilder()
                        .connectTimeout(config.connectTimeout())
                        .buildAsync(URI.create(url), new Listener())
                        .join();
                webSocket = socket;
                reconnectDelayMillis = config.reconnectInitialDelay().toMillis();
                logger.info("GATEWAY", "websocket connected shard=" + config.shardId()
                        + "/" + config.shardCount());
            } catch (Throwable error) {
                connecting.set(false);
                report(error);
                scheduleReconnect();
            }
        }, httpExecutor);
    }

    private void handleText(String text, boolean last) {
        if (!last) {
            return;
        }
        try {
            JsonNode raw = MAPPER.readTree(text);
            int op = raw.path("op").asInt(-1);
            if (raw.has("s") && !raw.get("s").isNull()) {
                sequence = raw.get("s").asLong();
            }
            switch (op) {
                case 0 -> dispatch(raw, op);
                case 1 -> sendHeartbeat();
                case 7 -> reconnect(false);
                case 9 -> {
                    sessionId = null;
                    sequence = null;
                    reconnect(true);
                }
                case 10 -> hello(raw.path("d").path("heartbeat_interval").asLong(45000));
                case 11 -> { /* Heartbeat ACK. */ }
                default -> logger.debug("GATEWAY", "unhandled opcode=" + op);
            }
        } catch (Exception error) {
            report(error);
        } finally {
            WebSocket socket = webSocket;
            if (socket != null) {
                socket.request(1);
            }
        }
    }

    private void dispatch(JsonNode raw, int op) {
        String type = raw.path("t").asText(null);
        Long seq = raw.has("s") && !raw.get("s").isNull() ? raw.get("s").asLong() : null;
        QQEvent event = new QQEvent(raw.path("id").asText(null), op, seq, type, raw.path("d"), raw);
        logger.debug("GATEWAY", "dispatch received type=" + type + " id=" + event.id() + " sequence=" + seq);
        if ("READY".equals(type)) {
            sessionId = raw.path("d").path("session_id").asText(null);
            readyFuture.complete(null);
            logger.info("GATEWAY", "ready shard=" + config.shardId() + "/" + config.shardCount());
        }
        bot.dispatch(event);
    }

    private void hello(long heartbeatIntervalMillis) {
        logger.debug("GATEWAY", "hello heartbeatIntervalMs=" + heartbeatIntervalMillis
                + " resumable=" + (sessionId != null && !sessionId.isBlank()));
        scheduleHeartbeat(Math.max(1000, heartbeatIntervalMillis));
        if (sessionId != null && !sessionId.isBlank()) {
            sendResume();
        } else {
            sendIdentify();
        }
    }

    private void scheduleHeartbeat(long intervalMillis) {
        ScheduledFuture<?> old = heartbeatTask;
        if (old != null) {
            old.cancel(false);
        }
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        ObjectNode payload = MAPPER.createObjectNode().put("op", 1);
        if (sequence == null) {
            payload.putNull("d");
        } else {
            payload.put("d", sequence);
        }
        send(payload);
        logger.trace("GATEWAY", "heartbeat sent sequence=" + sequence);
    }

    private void sendIdentify() {
        logger.debug("GATEWAY", "sending identify shard=" + config.shardId() + "/" + config.shardCount()
                + " intents=" + config.intents());
        ObjectNode data = MAPPER.createObjectNode()
                .put("token", "QQBot " + bot.accessToken())
                .put("intents", config.intents());
        ArrayNode shard = data.putArray("shard");
        shard.add(config.shardId()).add(config.shardCount());
        data.putObject("properties")
                .put("$os", System.getProperty("os.name", "unknown"))
                .put("$browser", "AstraQQBot")
                .put("$device", "AstraQQBot");
        send(MAPPER.createObjectNode().put("op", 2).set("d", data));
    }

    private void sendResume() {
        logger.debug("GATEWAY", "sending resume sequence=" + sequence);
        ObjectNode data = MAPPER.createObjectNode()
                .put("token", "QQBot " + bot.accessToken())
                .put("session_id", sessionId);
        if (sequence == null) {
            data.putNull("seq");
        } else {
            data.put("seq", sequence);
        }
        send(MAPPER.createObjectNode().put("op", 6).set("d", data));
    }

    private void send(ObjectNode payload) {
        WebSocket socket = webSocket;
        if (socket == null || stopped) {
            return;
        }
        socket.sendText(payload.toString(), true)
                .exceptionally(error -> {
                    report(error);
                    return null;
                });
    }

    private void reconnect(boolean identify) {
        if (identify) {
            sessionId = null;
            sequence = null;
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
        } else {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (stopped || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = reconnectDelayMillis;
        logger.warn("GATEWAY", "reconnect scheduled delayMs=" + delay);
        reconnectDelayMillis = Math.min(config.reconnectMaxDelay().toMillis(), Math.max(1000, delay * 2));
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connectAttempt();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void onClosed(int statusCode, String reason) {
        connecting.set(false);
        webSocket = null;
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        if (!stopped) {
            logger.warn("GATEWAY", "closed status=" + statusCode + " reason=" + reason);
            scheduleReconnect();
        }
    }

    private void report(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        bot.reportError("GATEWAY", cause);
    }

    @Override
    public void close() {
        stopped = true;
        logger.info("GATEWAY", "stopping shard=" + config.shardId() + "/" + config.shardCount());
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
        }
        scheduler.shutdownNow();
        if (!readyFuture.isDone()) {
            readyFuture.completeExceptionally(new IllegalStateException("gateway client closed"));
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            GatewayClient.this.webSocket = webSocket;
            connecting.set(false);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            handleText(data.toString(), last);
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed(statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            report(error);
        }
    }
}
