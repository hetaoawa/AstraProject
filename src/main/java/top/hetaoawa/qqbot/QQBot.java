package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point for the QQ official bot Java SDK. */
public final class QQBot implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(QQBot.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private final AccessTokenManager tokenManager;
    private final HttpApiClient api;
    private final GatewayClient gateway;
    private final QQOpenApi openApi;
    private final List<Consumer<QQEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QQMessageEvent>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QQInteractionEvent>> interactionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QQRelationshipEvent>> relationshipListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QQMessageStatusEvent>> messageStatusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<QQResourceEvent>> resourceListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<QQEvent>>> typedListeners = new ConcurrentHashMap<>();
    private volatile WebhookServer webhook;

    private QQBot(BotConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.tokenManager = new AccessTokenManager(config, httpClient);
        this.api = new HttpApiClient(config, httpClient, tokenManager);
        this.openApi = new QQOpenApi(api, config.appId());
        this.gateway = new GatewayClient(this, config, api, httpClient);
    }

    public static QQBot create(BotConfig config) {
        return new QQBot(config);
    }

    public BotConfig config() {
        return config;
    }

    public QQOpenApi api() {
        return openApi;
    }

    public QQBot onEvent(Consumer<QQEvent> listener) {
        eventListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onEvent(String type, Consumer<QQEvent> listener) {
        Objects.requireNonNull(type, "type");
        typedListeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onMessage(Consumer<QQMessageEvent> listener) {
        messageListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onInteraction(Consumer<QQInteractionEvent> listener) {
        interactionListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onRelationship(Consumer<QQRelationshipEvent> listener) {
        relationshipListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onMessageStatus(Consumer<QQMessageStatusEvent> listener) {
        messageStatusListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onResource(Consumer<QQResourceEvent> listener) {
        resourceListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public QQBot onError(Consumer<Throwable> listener) {
        errorListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Starts a reconnecting WebSocket Gateway client. The future completes after READY. */
    public CompletableFuture<Void> startWebSocket() {
        return gateway.start();
    }

    /** Starts the local HTTP callback listener; put TLS termination in front of it in production. */
    public synchronized WebhookServer startWebhook() throws IOException {
        if (webhook == null) {
            webhook = new WebhookServer(this, config).start();
        }
        return webhook;
    }

    public CompletableFuture<MessageResponse> sendPrivateMessage(String userOpenId, String content) {
        return sendPrivateMessage(userOpenId, MessagePayload.text(content));
    }

    public CompletableFuture<MessageResponse> sendPrivateMessage(String userOpenId, MessagePayload payload) {
        requireId(userOpenId, "userOpenId");
        return sendMessage("/v2/users/" + encodePathSegment(userOpenId) + "/messages", payload);
    }

    public CompletableFuture<MessageResponse> sendGroupMessage(String groupOpenId, String content) {
        return sendGroupMessage(groupOpenId, MessagePayload.text(content));
    }

    public CompletableFuture<MessageResponse> sendGroupMessage(String groupOpenId, MessagePayload payload) {
        requireId(groupOpenId, "groupOpenId");
        return sendMessage("/v2/groups/" + encodePathSegment(groupOpenId) + "/messages", payload);
    }

    public CompletableFuture<MessageResponse> replyText(QQMessageEvent event, String content) {
        Objects.requireNonNull(event, "event");
        MessagePayload payload = MessagePayload.text(content).replyTo(event);
        if (event.isGroupMessage()) {
            return sendGroupMessage(event.groupOpenId(), payload);
        }
        return sendPrivateMessage(event.userOpenId(), payload);
    }

    String accessToken() {
        try {
            return tokenManager.get();
        } catch (IOException e) {
            throw new RuntimeException("Unable to obtain QQ Bot access token", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while obtaining QQ Bot access token", e);
        }
    }

    void dispatch(QQEvent event) {
        if (event == null) {
            return;
        }
        if (event.type() != null) {
            for (Consumer<QQEvent> listener : typedListeners.getOrDefault(event.type(), List.of())) {
                invoke(() -> listener.accept(event));
            }
        }
        for (Consumer<QQEvent> listener : eventListeners) {
            invoke(() -> listener.accept(event));
        }
        if (isMessageEvent(event.type())) {
            QQMessageEvent message = QQMessageEvent.from(this, event);
            for (Consumer<QQMessageEvent> listener : messageListeners) {
                invoke(() -> listener.accept(message));
            }
        }
        if (QQInteractionEvent.supports(event.type())) {
            QQInteractionEvent interaction = QQInteractionEvent.from(this, event);
            interactionListeners.forEach(listener -> invoke(() -> listener.accept(interaction)));
        }
        if (QQRelationshipEvent.supports(event.type())) {
            QQRelationshipEvent relationship = QQRelationshipEvent.from(event);
            relationshipListeners.forEach(listener -> invoke(() -> listener.accept(relationship)));
        }
        if (QQMessageStatusEvent.supports(event.type())) {
            QQMessageStatusEvent status = QQMessageStatusEvent.from(event);
            messageStatusListeners.forEach(listener -> invoke(() -> listener.accept(status)));
        }
        if (QQResourceEvent.supports(event.type())) {
            QQResourceEvent resource = QQResourceEvent.from(event);
            resourceListeners.forEach(listener -> invoke(() -> listener.accept(resource)));
        }
    }

    void reportError(Throwable error) {
        Throwable actual = error == null ? new RuntimeException("Unknown QQ Bot error") : error;
        for (Consumer<Throwable> listener : errorListeners) {
            invoke(() -> listener.accept(actual));
        }
    }

    private CompletableFuture<MessageResponse> sendMessage(String path, MessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        ObjectNode body = payload.copyNode();
        return api.postAsync(path, body).thenApply(QQBot::messageResponse);
    }

    private static MessageResponse messageResponse(JsonNode body) {
        return new MessageResponse(body.path("id").asText(null), body.path("timestamp").asText(null), body);
    }

    private static boolean isMessageEvent(String type) {
        return "C2C_MESSAGE_CREATE".equals(type)
                || "GROUP_AT_MESSAGE_CREATE".equals(type)
                || "GROUP_MESSAGE_CREATE".equals(type);
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void invoke(Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            LOG.log(Level.WARNING, "QQ Bot event listener failed", error);
        }
    }

    @Override
    public synchronized void close() {
        gateway.close();
        if (webhook != null) {
            webhook.close();
            webhook = null;
        }
    }
}
