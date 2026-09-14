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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** QQ 官方机器人 Java SDK 的核心入口。 */
public final class QQBot implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotConfig config;
    private final HttpClient httpClient;
    private final ExecutorService httpExecutor;
    private final AccessTokenManager tokenManager;
    private final HttpApiClient api;
    private final GatewayClient gateway;
    private final QQOpenApi openApi;
    private final AstraLogger logger;
    private final List<NamedHandler<QQEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final List<NamedHandler<QQMessageEvent>> messageListeners = new CopyOnWriteArrayList<>();
    private final List<NamedHandler<QQInteractionEvent>> interactionListeners = new CopyOnWriteArrayList<>();
    private final List<NamedHandler<QQRelationshipEvent>> relationshipListeners = new CopyOnWriteArrayList<>();
    private final List<NamedHandler<QQMessageStatusEvent>> messageStatusListeners = new CopyOnWriteArrayList<>();
    private final List<NamedHandler<QQResourceEvent>> resourceListeners = new CopyOnWriteArrayList<>();
    private final List<NamedErrorHandler> errorListeners = new CopyOnWriteArrayList<>();
    private final List<CommandHandler> commandListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<NamedHandler<QQEvent>>> typedListeners = new ConcurrentHashMap<>();
    private final Map<String, PluginRuntime> pluginRuntimes = new ConcurrentHashMap<>();
    private final PluginRuntime defaultRuntime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebhookServer webhook;

    private QQBot(BotConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = new AstraLogger(config);
        AtomicInteger httpThreadId = new AtomicInteger();
        this.httpExecutor = Executors.newFixedThreadPool(config.httpExecutorThreads(), task -> {
            Thread thread = new Thread(task, "astraqqbot-http-" + config.shardId()
                    + "-" + httpThreadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.tokenManager = new AccessTokenManager(config, httpClient, logger);
        this.api = new HttpApiClient(config, httpClient, tokenManager, logger, httpExecutor);
        this.openApi = new QQOpenApi(api, config.appId());
        this.gateway = new GatewayClient(this, config, api, httpClient, logger, httpExecutor);
        this.defaultRuntime = new PluginRuntime(this, "bot", PluginExecutionOptions.defaults(config));
        logger.info("BOT", "created shard=" + config.shardId() + "/" + config.shardCount()
                + " intents=" + config.intents() + " httpThreads=" + config.httpExecutorThreads()
                + " maxConcurrentTasks=" + config.maxConcurrentTasks()
                + " maxPendingTasks=" + config.maxPendingTasks()
                + " pluginTaskTimeout=" + config.pluginTaskTimeout());
    }

    /**
     * 根据不可变配置创建 Bot 实例。
     *
     * @param config Bot 配置，不能为 {@code null}
     * @return 新建的 Bot 实例
     */
    public static QQBot create(BotConfig config) {
        return new QQBot(config);
    }

    /** 返回当前 Bot 的不可变配置。 */
    public BotConfig config() {
        return config;
    }

    /** 返回类型化的 OpenAPI 接口。 */
    public QQOpenApi api() {
        return openApi;
    }

    /**
     * 创建插件注册作用域，监听器名称会由插件名称自动生成。
     *
     * @param pluginName 插件名称，同时作为日志中的监听器名称前缀
     * @return 插件注册作用域
     */
    public synchronized Plugin plugin(String pluginName) {
        return plugin(pluginName, PluginExecutionOptions.defaults(config));
    }

    /** 创建使用指定执行配置的插件注册作用域。 */
    public synchronized Plugin plugin(String pluginName, PluginExecutionOptions options) {
        if (closed.get()) {
            throw new IllegalStateException("QQBot is closed");
        }
        String name = requireHandlerName(pluginName);
        Objects.requireNonNull(options, "options");
        PluginRuntime runtime = pluginRuntimes.compute(name, (ignored, existing) -> {
            if (existing == null || existing.isClosed()) {
                return new PluginRuntime(this, name, options);
            }
            if (!existing.options().equals(options)) {
                throw new IllegalStateException("Plugin '" + name
                        + "' was already created with different execution options");
            }
            return existing;
        });
        return new Plugin(this, runtime);
    }

    /** 注册未命名的同步原始事件监听器。 */
    public QQBot onEvent(EventHandler<QQEvent> listener) {
        return onEventNamed(autoName(listener), listener);
    }

    /** 注册指定名称的同步原始事件监听器。 */
    public QQBot onEventNamed(String handlerName, EventHandler<QQEvent> listener) {
        eventListeners.add(handler(defaultRuntime, handlerName, "event", listener));
        logger.debug("PLUGIN", "registered event handler=" + handlerName);
        return this;
    }

    /** 注册未命名的指定事件类型同步监听器。 */
    public QQBot onEvent(String type, EventHandler<QQEvent> listener) {
        return onEvent(type, autoName(listener), listener);
    }

    /** 注册指定名称的指定事件类型同步监听器。 */
    public QQBot onEvent(String type, String handlerName, EventHandler<QQEvent> listener) {
        requireEventType(type);
        typedListeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                .add(handler(defaultRuntime, handlerName, "typed-event", listener));
        logger.debug("PLUGIN", "registered typed-event handler=" + handlerName + " type=" + type);
        return this;
    }

    /** 注册未命名的同步标准化消息监听器。 */
    public QQBot onMessage(EventHandler<QQMessageEvent> listener) {
        return onMessage(autoName(listener), listener);
    }

    /** 注册指定名称的同步标准化消息监听器。 */
    public QQBot onMessage(String handlerName, EventHandler<QQMessageEvent> listener) {
        messageListeners.add(handler(defaultRuntime, handlerName, "message", listener));
        logger.debug("PLUGIN", "registered message handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步互动监听器。 */
    public QQBot onInteraction(EventHandler<QQInteractionEvent> listener) {
        return onInteraction(autoName(listener), listener);
    }

    /** 注册指定名称的同步互动监听器。 */
    public QQBot onInteraction(String handlerName, EventHandler<QQInteractionEvent> listener) {
        interactionListeners.add(handler(defaultRuntime, handlerName, "interaction", listener));
        logger.debug("PLUGIN", "registered interaction handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步关系事件监听器。 */
    public QQBot onRelationship(EventHandler<QQRelationshipEvent> listener) {
        return onRelationship(autoName(listener), listener);
    }

    /** 注册指定名称的同步关系事件监听器。 */
    public QQBot onRelationship(String handlerName, EventHandler<QQRelationshipEvent> listener) {
        relationshipListeners.add(handler(defaultRuntime, handlerName, "relationship", listener));
        logger.debug("PLUGIN", "registered relationship handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步消息状态监听器。 */
    public QQBot onMessageStatus(EventHandler<QQMessageStatusEvent> listener) {
        return onMessageStatus(autoName(listener), listener);
    }

    /** 注册指定名称的同步消息状态监听器。 */
    public QQBot onMessageStatus(String handlerName, EventHandler<QQMessageStatusEvent> listener) {
        messageStatusListeners.add(handler(defaultRuntime, handlerName, "message-status", listener));
        logger.debug("PLUGIN", "registered message-status handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步资源监听器。 */
    public QQBot onResource(EventHandler<QQResourceEvent> listener) {
        return onResource(autoName(listener), listener);
    }

    /** 注册指定名称的同步资源监听器。 */
    public QQBot onResource(String handlerName, EventHandler<QQResourceEvent> listener) {
        resourceListeners.add(handler(defaultRuntime, handlerName, "resource", listener));
        logger.debug("PLUGIN", "registered resource handler=" + handlerName);
        return this;
    }

    private QQBot onCommand(String handlerName, String command, int priority,
                            boolean continuePropagation, EventHandler<QQCommandEvent> listener) {
        commandListeners.add(new CommandHandler(requireHandlerName(handlerName), normalizeCommand(command),
                priority, continuePropagation, defaultRuntime, listener));
        logger.debug("PLUGIN", "registered command handler=" + handlerName + " command=" + command
                + " priority=" + priority + " continue=" + continuePropagation);
        return this;
    }


    /** 注册未命名的错误监听器。 */
    public QQBot onError(EventHandler<Throwable> listener) {
        return onError(autoName(listener), listener);
    }

    /** 注册指定名称的错误监听器。 */
    public QQBot onError(String handlerName, EventHandler<Throwable> listener) {
        Objects.requireNonNull(listener, "listener");
        errorListeners.add(new NamedErrorHandler(requireHandlerName(handlerName), defaultRuntime, listener));
        logger.debug("PLUGIN", "registered error handler=" + handlerName);
        return this;
    }


    /** Listener registration scope for one plugin. */
    public static final class Plugin implements AutoCloseable {
        private final QQBot bot;
        private final PluginRuntime runtime;

        private Plugin(QQBot bot, PluginRuntime runtime) {
            this.bot = bot;
            this.runtime = runtime;
        }

        /** 返回用于生成监听器名称前缀的插件名称。 */
        public String name() {
            return runtime.name();
        }

        /** 返回当前插件作用域的执行配置。 */
        public PluginExecutionOptions executionOptions() {
            return runtime.options();
        }

        /** 注册在当前插件执行器中运行的原始事件监听器。 */
        public Plugin onEvent(EventHandler<QQEvent> listener) {
            String name = handler("event");
            bot.eventListeners.add(QQBot.handler(runtime, name, "event", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的指定类型事件监听器。 */
        public Plugin onEvent(String type, EventHandler<QQEvent> listener) {
            String name = handler("event." + type);
            requireEventType(type);
            bot.typedListeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                    .add(QQBot.handler(runtime, name, "typed-event", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的消息监听器。 */
        public Plugin onMessage(EventHandler<QQMessageEvent> listener) {
            String name = handler("message");
            bot.messageListeners.add(QQBot.handler(runtime, name, "message", listener));
            return this;
        }

        /**
         * 注册在当前插件执行器中运行的带优先级命令监听器。
         *
         * @param command 要匹配的命令名，不包含前缀
         * @param priority 优先级，数值越大越先执行
         * @param continuePropagation 是否允许继续执行下一个匹配监听器
         * @param listener 命令处理函数
         * @return 当前插件作用域
         */
        public Plugin onCommand(String command, int priority, boolean continuePropagation,
                                EventHandler<QQCommandEvent> listener) {
            String name = handler("command." + command);
            bot.commandListeners.add(new CommandHandler(name, normalizeCommand(command), priority,
                    continuePropagation, runtime, Objects.requireNonNull(listener, "listener")));
            return this;
        }

        /** 注册在当前插件执行器中运行的互动监听器。 */
        public Plugin onInteraction(EventHandler<QQInteractionEvent> listener) {
            String name = handler("interaction");
            bot.interactionListeners.add(QQBot.handler(runtime, name, "interaction", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的关系事件监听器。 */
        public Plugin onRelationship(EventHandler<QQRelationshipEvent> listener) {
            String name = handler("relationship");
            bot.relationshipListeners.add(QQBot.handler(runtime, name, "relationship", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的消息状态监听器。 */
        public Plugin onMessageStatus(EventHandler<QQMessageStatusEvent> listener) {
            String name = handler("message-status");
            bot.messageStatusListeners.add(QQBot.handler(runtime, name, "message-status", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的资源监听器。 */
        public Plugin onResource(EventHandler<QQResourceEvent> listener) {
            String name = handler("resource");
            bot.resourceListeners.add(QQBot.handler(runtime, name, "resource", listener));
            return this;
        }

        /** 在当前插件作用域注册错误监听器。 */
        public Plugin onError(EventHandler<Throwable> listener) {
            String name = handler("error");
            bot.errorListeners.add(new NamedErrorHandler(name, runtime, listener));
            return this;
        }

        public boolean isClosing() { return runtime.isClosing(); }
        public boolean isClosed() { return runtime.isClosed(); }

        @Override public void close() { bot.closePlugin(runtime); }

        private String handler(String kind) {
            return runtime.nextHandler(kind);
        }
    }

    /** 启动可自动重连的 WebSocket Gateway；Future 在收到 READY 后完成。 */
    public CompletableFuture<Void> startWebSocket() {
        return gateway.start();
    }

    /** 启动本地 HTTP 回调服务；生产环境应在前置网关完成 TLS 终止。 */
    public synchronized WebhookServer startWebhook() throws IOException {
        if (webhook == null) {
            webhook = new WebhookServer(this, config, logger).start();
        }
        return webhook;
    }

    /** 发送私聊文本消息。 */
    public MessageResponse sendPrivateMessage(String userOpenId, String content) {
        return sendPrivateMessage(userOpenId, MessagePayload.text(content));
    }

    /** 发送已构造的私聊消息载荷。 */
    public MessageResponse sendPrivateMessage(String userOpenId, MessagePayload payload) {
        requireId(userOpenId, "userOpenId");
        return sendMessage("/v2/users/" + encodePathSegment(userOpenId) + "/messages", payload);
    }

    /** 发送群聊文本消息。 */
    public MessageResponse sendGroupMessage(String groupOpenId, String content) {
        return sendGroupMessage(groupOpenId, MessagePayload.text(content));
    }

    /** 发送已构造的群聊消息载荷。 */
    public MessageResponse sendGroupMessage(String groupOpenId, MessagePayload payload) {
        requireId(groupOpenId, "groupOpenId");
        return sendMessage("/v2/groups/" + encodePathSegment(groupOpenId) + "/messages", payload);
    }

    private void closePlugin(PluginRuntime runtime) {
        if (runtime == defaultRuntime) throw new IllegalStateException("default plugin runtime is owned by QQBot");
        eventListeners.removeIf(handler -> handler.runtime() == runtime);
        typedListeners.values().forEach(list -> list.removeIf(handler -> handler.runtime() == runtime));
        messageListeners.removeIf(handler -> handler.runtime() == runtime);
        interactionListeners.removeIf(handler -> handler.runtime() == runtime);
        relationshipListeners.removeIf(handler -> handler.runtime() == runtime);
        messageStatusListeners.removeIf(handler -> handler.runtime() == runtime);
        resourceListeners.removeIf(handler -> handler.runtime() == runtime);
        commandListeners.removeIf(handler -> handler.runtime() == runtime);
        errorListeners.removeIf(handler -> handler.runtime() == runtime);
        runtime.beginShutdown();
        runtime.awaitShutdown(System.nanoTime() + config.pluginShutdownTimeout().toNanos(),
                config.pluginShutdownTimeout());
        pluginRuntimes.remove(runtime.name(), runtime);
    }

    /** 发送文字子频道文本消息。 */
    public MessageResponse sendChannelMessage(String channelId, String content) {
        return sendChannelMessage(channelId, MessagePayload.text(content));
    }

    /** 发送已构造的文字子频道消息载荷。 */
    public MessageResponse sendChannelMessage(String channelId, MessagePayload payload) {
        requireId(channelId, "channelId");
        return sendChannelStyleMessage("/channels/" + encodePathSegment(channelId) + "/messages", payload);
    }

    /** 使用 multipart/form-data 向文字子频道直接上传并发送图片。 */
    public MessageResponse sendChannelImage(String channelId, MessagePayload payload,
                                                                String fileName, String contentType, byte[] image) {
        requireId(channelId, "channelId");
        return sendChannelStyleImage("/channels/" + encodePathSegment(channelId) + "/messages",
                payload, fileName, contentType, image);
    }

    /** 发送频道私信文本消息，其中 {@code guildId} 是创建会话或私信事件返回的私信 Guild ID。 */
    public MessageResponse sendDirectMessage(String guildId, String content) {
        return sendDirectMessage(guildId, MessagePayload.text(content));
    }

    /** 发送已构造的频道私信消息载荷。 */
    public MessageResponse sendDirectMessage(String guildId, MessagePayload payload) {
        requireId(guildId, "guildId");
        return sendChannelStyleMessage("/dms/" + encodePathSegment(guildId) + "/messages", payload);
    }

    /** 使用 multipart/form-data 向频道私信会话直接上传并发送图片。 */
    public MessageResponse sendDirectImage(String guildId, MessagePayload payload,
                                                               String fileName, String contentType, byte[] image) {
        requireId(guildId, "guildId");
        return sendChannelStyleImage("/dms/" + encodePathSegment(guildId) + "/messages",
                payload, fileName, contentType, image);
    }

    /** 回复标准化消息事件，并发送文本内容。 */
    public MessageResponse replyText(QQMessageEvent event, String content) {
        Objects.requireNonNull(event, "event");
        MessagePayload payload = MessagePayload.text(content).replyTo(event);
        if (event.isGroupMessage()) {
            return sendGroupMessage(event.groupOpenId(), payload);
        }
        if (event.isDirectMessage()) {
            return sendDirectMessage(event.guildId(), payload);
        }
        if (event.isChannelMessage()) {
            return sendChannelMessage(event.channelId(), payload);
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
        logger.debug("EVENT", "received type=" + event.type() + " id=" + event.id()
                + " op=" + event.op() + " sequence=" + event.sequence());
        if (logger.eventPayloadsEnabled()) {
            logger.trace("EVENT", "payload type=" + event.type() + " data=" + event.data());
        }
        int captured = 0;
        if (event.type() != null) {
            for (NamedHandler<QQEvent> listener : typedListeners.getOrDefault(event.type(), List.of())) {
                invokeHandler("typed-event", event, listener);
                captured++;
            }
        }
        for (NamedHandler<QQEvent> listener : eventListeners) {
            invokeHandler("event", event, listener);
            captured++;
        }
        if (isMessageEvent(event.type())) {
            QQMessageEvent message = QQMessageEvent.from(this, event);
            for (NamedHandler<QQMessageEvent> listener : messageListeners) {
                invokeHandler("message", event, listener, message);
                captured++;
            }
            captured += dispatchCommandListeners(event, message);
        }
        if (QQInteractionEvent.supports(event.type())) {
            QQInteractionEvent interaction = QQInteractionEvent.from(this, event);
            for (NamedHandler<QQInteractionEvent> listener : interactionListeners) {
                invokeHandler("interaction", event, listener, interaction);
                captured++;
            }
        }
        if (QQRelationshipEvent.supports(event.type())) {
            QQRelationshipEvent relationship = QQRelationshipEvent.from(event);
            for (NamedHandler<QQRelationshipEvent> listener : relationshipListeners) {
                invokeHandler("relationship", event, listener, relationship);
                captured++;
            }
        }
        if (QQMessageStatusEvent.supports(event.type())) {
            QQMessageStatusEvent status = QQMessageStatusEvent.from(event);
            for (NamedHandler<QQMessageStatusEvent> listener : messageStatusListeners) {
                invokeHandler("message-status", event, listener, status);
                captured++;
            }
        }
        if (QQResourceEvent.supports(event.type())) {
            QQResourceEvent resource = QQResourceEvent.from(event);
            for (NamedHandler<QQResourceEvent> listener : resourceListeners) {
                invokeHandler("resource", event, listener, resource);
                captured++;
            }
        }
        logger.debug("EVENT", "routed type=" + event.type() + " id=" + event.id() + " handlers=" + captured);
    }

    void reportError(Throwable error) {
        reportError("BOT", error);
    }

    private int dispatchCommandListeners(QQEvent event, QQMessageEvent message) {
        ParsedCommand parsed = parseCommand(message.content());
        if (parsed == null) {
            return 0;
        }
        List<CommandHandler> matching = commandListeners.stream()
                .filter(listener -> listener.command().equalsIgnoreCase(parsed.command()))
                .sorted((left, right) -> Integer.compare(right.priority(), left.priority()))
                .toList();
        if (matching.isEmpty()) {
            return 0;
        }
        QQCommandEvent commandEvent = new QQCommandEvent(message, parsed.prefix(), parsed.tokens());
        logger.debug("COMMAND", "matched prefix=" + parsed.prefix() + " command=" + parsed.command()
                + " arguments=" + commandEvent.arguments().size() + " listeners=" + matching.size());

        AtomicBoolean stopped = new AtomicBoolean();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (CommandHandler listener : matching) {
            chain = chain.thenCompose(ignored -> {
                if (stopped.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                return invokeCommandHandler(event, listener, commandEvent)
                        .thenRun(() -> stopped.set(!listener.continuePropagation()));
            });
        }
        chain.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.error("COMMAND", "dispatch failed command=" + parsed.command(), unwrap(error));
            }
        });
        return matching.size();
    }

    private CompletableFuture<Void> invokeCommandHandler(QQEvent event, CommandHandler handler,
                                                       QQCommandEvent commandEvent) {
        return handler.runtime().submit(handler.name(), "command", commandEvent, handler.action());
    }

    private ParsedCommand parseCommand(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String input = content.stripLeading();
        String prefix = config.commandPrefixes().stream()
                .filter(input::startsWith)
                .findFirst()
                .orElse(null);
        if (prefix == null) {
            return null;
        }
        String body = input.substring(prefix.length()).strip();
        if (body.isBlank()) {
            return null;
        }
        List<String> tokens = Pattern.compile(Pattern.quote(config.commandSeparator()))
                .splitAsStream(body)
                .map(String::strip)
                .filter(token -> !token.isBlank())
                .toList();
        return tokens.isEmpty() ? null : new ParsedCommand(prefix, tokens.get(0), tokens);
    }

    void reportError(String component, Throwable error) {
        Throwable actual = error == null ? new RuntimeException("Unknown QQ Bot error") : error;
        logger.error(component, "reported error=" + actual.getClass().getSimpleName()
                + " message=" + actual.getMessage(), actual);
        for (NamedErrorHandler listener : errorListeners) {
            try {
                listener.runtime().submit(listener.name(), "error", actual, listener.action())
                        .exceptionally(listenerError -> {
                            logger.error("PLUGIN", "error handler failed name=" + listener.name(),
                                    unwrap(listenerError));
                            return null;
                        });
            } catch (Throwable listenerError) {
                logger.error("PLUGIN", "error handler failed name=" + listener.name(), listenerError);
            }
        }
    }

    private MessageResponse sendMessage(String path, MessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        ObjectNode body = payload.copyNode();
        return messageResponse(await(api.postAsync(path, body)));
    }

    private MessageResponse sendChannelStyleMessage(String path, MessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        return messageResponse(await(api.postAsync(path, payload.copyChannelNode())));
    }

    private MessageResponse sendChannelStyleImage(String path, MessagePayload payload,
                                                                      String fileName, String contentType,
                                                                      byte[] image) {
        Objects.requireNonNull(payload, "payload");
        return messageResponse(await(api.postMultipartAsync(path, payload.copyChannelNode(),
                fileName, contentType, image)));
    }

    private static MessageResponse messageResponse(JsonNode body) {
        return new MessageResponse(body.path("id").asText(null), body.path("timestamp").asText(null), body);
    }

    private static boolean isMessageEvent(String type) {
        return "C2C_MESSAGE_CREATE".equals(type)
                || "GROUP_AT_MESSAGE_CREATE".equals(type)
                || "GROUP_MESSAGE_CREATE".equals(type)
                || "AT_MESSAGE_CREATE".equals(type)
                || "MESSAGE_CREATE".equals(type)
                || "DIRECT_MESSAGE_CREATE".equals(type);
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

    AstraLogger logger() {
        return logger;
    }

    private <T> void invokeHandler(String kind, QQEvent event, NamedHandler<T> handler, T value) {
        handler.runtime().submit(handler.name(), kind, value, handler.action());
    }

    private void invokeHandler(String kind, QQEvent event, NamedHandler<QQEvent> handler) {
        invokeHandler(kind, event, handler, event);
    }

    private void logHandlerCompleted(String kind, QQEvent event, String handlerName, long started) {
        logger.debug("PLUGIN", "completed handler=" + handlerName + " kind=" + kind
                + " eventType=" + event.type() + " eventId=" + event.id()
                + " elapsedMs=" + elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
    }

    private static void requireEventType(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
    }

    private static String autoName(Object listener) {
        Objects.requireNonNull(listener, "listener");
        return listener.getClass().getName();
    }

    private static String requireHandlerName(String handlerName) {
        if (handlerName == null || handlerName.isBlank()) {
            throw new IllegalArgumentException("handlerName must not be blank");
        }
        return handlerName;
    }

    private static String normalizeCommand(String command) {
        String value = requireHandlerName(command);
        if (value.contains(" ") || value.contains("\t") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("command must be a single token");
        }
        return value;
    }

    private static <T> NamedHandler<T> handler(PluginRuntime runtime, String name, String kind,
                                                EventHandler<T> listener) {
        return new NamedHandler<>(requireHandlerName(name), kind, runtime,
                Objects.requireNonNull(listener, "listener"));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for QQ OpenAPI", error);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new RuntimeException(cause);
        }
    }

    private static final class PluginRuntime {
        private final QQBot bot;
        private final String name;
        private final PluginExecutionOptions options;
        private final Duration taskTimeout;
        private final Map<String, AtomicInteger> handlerCounts = new ConcurrentHashMap<>();
        private final Object lock = new Object();
        private final java.util.ArrayDeque<ManagedPluginTask<?>> pending = new java.util.ArrayDeque<>();
        private final java.util.Set<ManagedPluginTask<?>> tasks = ConcurrentHashMap.newKeySet();
        private final ExecutorService executor;
        private final ScheduledExecutorService scheduler;
        private boolean closing;
        private boolean closed;
        private int active;

        private PluginRuntime(QQBot bot, String name, PluginExecutionOptions options) {
            this.bot = bot;
            this.name = name;
            this.options = options;
            this.taskTimeout = options.effectiveTaskTimeout(bot.config.pluginTaskTimeout());
            String prefix = "astraqqbot-plugin-" + bot.config.shardId() + "-" + sanitizeThreadName(name) + "-";
            this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, prefix + "timeout");
                thread.setDaemon(true);
                return thread;
            });
        }

        private String name() { return name; }
        private PluginExecutionOptions options() { return options; }
        private boolean isClosing() { synchronized (lock) { return closing; } }
        private boolean isClosed() { synchronized (lock) { return closed; } }
        private String nextHandler(String kind) {
            synchronized (lock) {
                if (closing) throw new IllegalStateException("Plugin '" + name + "' is closed");
            }
            int occurrence = handlerCounts.computeIfAbsent(kind, ignored -> new AtomicInteger()).incrementAndGet();
            return name + "." + kind + (occurrence == 1 ? "" : "#" + occurrence);
        }

        private <T> CompletableFuture<Void> submit(String handlerName, String kind, T value,
                                                   EventHandler<T> action) {
            ManagedPluginTask<T> task = new ManagedPluginTask<>(this, handlerName, kind, value, action);
            boolean start = false;
            RejectedExecutionException rejection = null;
            synchronized (lock) {
                if (closing) {
                    rejection = reject(task, "plugin is closing");
                } else if (active < options.maxConcurrentTasks()) {
                    active++;
                    task.activeSlot = true;
                    tasks.add(task);
                    start = true;
                } else if (pending.size() < options.maxPendingTasks()) {
                    pending.addLast(task);
                    tasks.add(task);
                } else {
                    rejection = reject(task, "pending queue is full");
                }
            }
            if (rejection != null) {
                if (!"error".equals(kind)) bot.reportError("PLUGIN", rejection);
                return task.result;
            }
            if (start) start(task);
            bot.logger.debug("PLUGIN", "queued plugin=" + name + " handler=" + handlerName
                    + " kind=" + kind + " eventType=" + eventType(value) + " eventId=" + eventId(value));
            return task.result;
        }

        private RejectedExecutionException reject(ManagedPluginTask<?> task, String reason) {
            RejectedExecutionException error = new RejectedExecutionException("Plugin '" + name
                    + "' rejected handler '" + task.handlerName + "': " + reason
                    + ", eventType=" + eventType(task.value) + ", eventId=" + eventId(task.value)
                    + ", active=" + active + "/" + options.maxConcurrentTasks()
                    + ", pending=" + pending.size() + "/" + options.maxPendingTasks());
            task.state.set(PluginTaskState.CANCELLED);
            task.endedAtMillis = System.currentTimeMillis();
            task.result.completeExceptionally(error);
            return error;
        }

        private void start(ManagedPluginTask<?> task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException error) {
                finish(task, PluginTaskState.CANCELLED, error);
            }
        }

        private void started(ManagedPluginTask<?> task) {
            if (taskTimeout != null) {
                task.timeoutFuture = scheduler.schedule(() -> {
                    TimeoutException error = new TimeoutException("Plugin '" + name + "' handler '"
                            + task.handlerName + "' timed out after " + taskTimeout
                            + ", eventType=" + eventType(task.value) + ", eventId=" + eventId(task.value));
                    if (finish(task, PluginTaskState.TIMED_OUT, error)) {
                        Thread thread = task.thread;
                        if (thread != null) thread.interrupt();
                    }
                }, taskTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        private boolean finish(ManagedPluginTask<?> task, PluginTaskState target, Throwable error) {
            PluginTaskState expected = target == PluginTaskState.CANCELLED
                    ? task.state.get() : PluginTaskState.RUNNING;
            if ((expected != PluginTaskState.QUEUED && expected != PluginTaskState.RUNNING)
                    || !task.state.compareAndSet(expected, target)) return false;
            task.endedAtMillis = System.currentTimeMillis();
            ScheduledFuture<?> timer = task.timeoutFuture;
            if (timer != null) timer.cancel(false);
            if (error == null) task.result.complete(null); else task.result.completeExceptionally(error);

            long queueMs = task.startedAtMillis == 0 ? 0 : task.startedAtMillis - task.queuedAtMillis;
            long elapsedMs = task.startedAtMillis == 0 ? 0 : task.endedAtMillis - task.startedAtMillis;
            String detail = "plugin=" + name + " handler=" + task.handlerName + " kind=" + task.kind
                    + " eventType=" + eventType(task.value) + " eventId=" + eventId(task.value)
                    + " queuedAt=" + task.queuedAtMillis + " startedAt=" + task.startedAtMillis
                    + " endedAt=" + task.endedAtMillis + " queueMs=" + queueMs + " elapsedMs=" + elapsedMs
                    + " state=" + target + " thread=" + (task.thread == null ? "none" : task.thread.getName());
            if (error == null) bot.logger.debug("PLUGIN", "completed " + detail);
            else bot.logger.error("PLUGIN", "failed " + detail, error);

            ManagedPluginTask<?> next = null;
            synchronized (lock) {
                tasks.remove(task);
                if (task.activeSlot) {
                    task.activeSlot = false;
                    active--;
                }
                while (!pending.isEmpty() && next == null) {
                    ManagedPluginTask<?> candidate = pending.removeFirst();
                    if (candidate.state.get() == PluginTaskState.QUEUED) next = candidate;
                }
                if (next != null) {
                    active++;
                    next.activeSlot = true;
                }
                lock.notifyAll();
            }
            if (next != null) start(next);
            if (error != null && !"error".equals(task.kind)) bot.reportError("PLUGIN", error);
            return true;
        }

        private void beginShutdown() {
            List<ManagedPluginTask<?>> queued;
            synchronized (lock) {
                if (closing) return;
                closing = true;
                queued = List.copyOf(pending);
                pending.clear();
            }
            CancellationException error = new CancellationException("Plugin '" + name + "' is closing");
            queued.forEach(task -> finish(task, PluginTaskState.CANCELLED, error));
        }

        private void awaitShutdown(long deadline, Duration configuredTimeout) {
            boolean interrupted = false;
            synchronized (lock) {
                while (!tasks.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try { TimeUnit.NANOSECONDS.timedWait(lock, remaining); }
                    catch (InterruptedException error) { interrupted = true; break; }
                }
            }
            if (!tasks.isEmpty()) {
                CancellationException error = new CancellationException("Plugin '" + name
                        + "' did not stop within " + configuredTimeout);
                for (ManagedPluginTask<?> task : List.copyOf(tasks)) {
                    if (finish(task, PluginTaskState.CANCELLED, error)) {
                        Thread thread = task.thread;
                        if (thread != null) thread.interrupt();
                    }
                }
            }
            scheduler.shutdownNow();
            executor.shutdownNow();
            synchronized (lock) { closed = true; lock.notifyAll(); }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static String sanitizeThreadName(String value) {
            String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
            return sanitized.length() <= 48 ? sanitized : sanitized.substring(0, 48);
        }

        private static String eventType(Object value) {
            if (value instanceof QQEvent event) return event.type();
            if (value instanceof QQMessageEvent event) return event.eventType();
            if (value instanceof QQInteractionEvent event) return event.eventType();
            if (value instanceof QQRelationshipEvent event) return event.eventType();
            if (value instanceof QQMessageStatusEvent event) return event.eventType();
            if (value instanceof QQResourceEvent event) return event.eventType();
            if (value instanceof QQCommandEvent event) return event.message().eventType();
            return null;
        }

        private static String eventId(Object value) {
            if (value instanceof QQEvent event) return event.id();
            if (value instanceof QQMessageEvent event) return event.eventId();
            if (value instanceof QQInteractionEvent event) return event.eventId();
            if (value instanceof QQRelationshipEvent event) return event.eventId();
            if (value instanceof QQMessageStatusEvent event) return event.eventId();
            if (value instanceof QQResourceEvent event) return event.eventId();
            if (value instanceof QQCommandEvent event) return event.message().eventId();
            return null;
        }

    }

    private static final class ManagedPluginTask<T> implements Runnable {
        private final PluginRuntime runtime;
        private final String handlerName;
        private final String kind;
        private final T value;
        private final EventHandler<T> action;
        private final AtomicReference<PluginTaskState> state = new AtomicReference<>(PluginTaskState.QUEUED);
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final long queuedAtMillis = System.currentTimeMillis();
        private volatile long startedAtMillis;
        private volatile long endedAtMillis;
        private volatile Thread thread;
        private volatile ScheduledFuture<?> timeoutFuture;
        private volatile boolean activeSlot;

        private ManagedPluginTask(PluginRuntime runtime, String handlerName, String kind, T value,
                                  EventHandler<T> action) {
            this.runtime = runtime;
            this.handlerName = handlerName;
            this.kind = kind;
            this.value = value;
            this.action = action;
        }

        @Override public void run() {
            if (!state.compareAndSet(PluginTaskState.QUEUED, PluginTaskState.RUNNING)) return;
            thread = Thread.currentThread();
            startedAtMillis = System.currentTimeMillis();
            runtime.started(this);
            try {
                action.handle(value);
                runtime.finish(this, PluginTaskState.SUCCEEDED, null);
            } catch (Throwable error) {
                runtime.finish(this, PluginTaskState.FAILED, error);
                if (error instanceof VirtualMachineError fatal) throw fatal;
                if (error instanceof ThreadDeath fatal) throw fatal;
                if (error instanceof LinkageError fatal) throw fatal;
            }
        }
    }

    private record NamedHandler<T>(String name, String kind, PluginRuntime runtime, EventHandler<T> action) {
    }

    private record CommandHandler(String name, String command, int priority, boolean continuePropagation,
                                  PluginRuntime runtime, EventHandler<QQCommandEvent> action) {
    }

    private record ParsedCommand(String prefix, String command, List<String> tokens) {
    }

    private record NamedErrorHandler(String name, PluginRuntime runtime, EventHandler<Throwable> action) {
    }

    /** 关闭 Gateway、Webhook、调度器及其他由 Bot 持有的资源。 */
    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        logger.info("BOT", "closing shard=" + config.shardId() + "/" + config.shardCount());
        gateway.close();
        if (webhook != null) {
            webhook.close();
            webhook = null;
        }
        List<PluginRuntime> runtimes = new java.util.ArrayList<>(pluginRuntimes.values());
        runtimes.add(defaultRuntime);
        for (PluginRuntime runtime : runtimes) {
            runtime.beginShutdown();
        }
        long pluginDeadline = System.nanoTime() + config.pluginShutdownTimeout().toNanos();
        for (PluginRuntime runtime : runtimes) {
            runtime.awaitShutdown(pluginDeadline, config.pluginShutdownTimeout());
        }
        httpExecutor.shutdownNow();
        logger.info("BOT", "closed shard=" + config.shardId() + "/" + config.shardCount());
    }
}
