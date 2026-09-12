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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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
        logger.info("BOT", "created shard=" + config.shardId() + "/" + config.shardCount()
                + " intents=" + config.intents() + " httpThreads=" + config.httpExecutorThreads()
                + " pluginThreads=" + config.pluginExecutorThreads()
                + " pluginQueueCapacity=" + config.pluginQueueCapacity());
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
        return plugin(pluginName, PluginExecutionOptions.of(
                config.pluginExecutorThreads(), config.pluginQueueCapacity()));
    }

    /** 创建使用指定执行配置的插件注册作用域。 */
    public synchronized Plugin plugin(String pluginName, PluginExecutionOptions options) {
        if (closed.get()) {
            throw new IllegalStateException("QQBot is closed");
        }
        String name = requireHandlerName(pluginName);
        Objects.requireNonNull(options, "options");
        PluginRuntime runtime = pluginRuntimes.compute(name, (ignored, existing) -> {
            if (existing == null) {
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
    public QQBot onEvent(Consumer<QQEvent> listener) {
        return onEventNamed(autoName(listener), listener);
    }

    /** 注册指定名称的同步原始事件监听器。 */
    public QQBot onEventNamed(String handlerName, Consumer<QQEvent> listener) {
        eventListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered event handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步原始事件监听器，返回的 Stage 完成后记录处理完成。 */
    public QQBot onEventAsync(String handlerName,
                              Function<QQEvent, ? extends CompletionStage<?>> listener) {
        eventListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async event handler=" + handlerName);
        return this;
    }

    /** 注册未命名的指定事件类型同步监听器。 */
    public QQBot onEvent(String type, Consumer<QQEvent> listener) {
        return onEvent(type, autoName(listener), listener);
    }

    /** 注册指定名称的指定事件类型同步监听器。 */
    public QQBot onEvent(String type, String handlerName, Consumer<QQEvent> listener) {
        requireEventType(type);
        typedListeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                .add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered typed-event handler=" + handlerName + " type=" + type);
        return this;
    }

    /** 注册指定名称的指定事件类型异步监听器。 */
    public QQBot onEventAsync(String type, String handlerName,
                              Function<QQEvent, ? extends CompletionStage<?>> listener) {
        requireEventType(type);
        typedListeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
                .add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async typed-event handler=" + handlerName + " type=" + type);
        return this;
    }

    /** 注册未命名的同步标准化消息监听器。 */
    public QQBot onMessage(Consumer<QQMessageEvent> listener) {
        return onMessage(autoName(listener), listener);
    }

    /** 注册指定名称的同步标准化消息监听器。 */
    public QQBot onMessage(String handlerName, Consumer<QQMessageEvent> listener) {
        messageListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered message handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步标准化消息监听器。 */
    public QQBot onMessageAsync(String handlerName,
                                Function<QQMessageEvent, ? extends CompletionStage<?>> listener) {
        messageListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async message handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步互动监听器。 */
    public QQBot onInteraction(Consumer<QQInteractionEvent> listener) {
        return onInteraction(autoName(listener), listener);
    }

    /** 注册指定名称的同步互动监听器。 */
    public QQBot onInteraction(String handlerName, Consumer<QQInteractionEvent> listener) {
        interactionListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered interaction handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步互动监听器。 */
    public QQBot onInteractionAsync(String handlerName,
                                    Function<QQInteractionEvent, ? extends CompletionStage<?>> listener) {
        interactionListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async interaction handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步关系事件监听器。 */
    public QQBot onRelationship(Consumer<QQRelationshipEvent> listener) {
        return onRelationship(autoName(listener), listener);
    }

    /** 注册指定名称的同步关系事件监听器。 */
    public QQBot onRelationship(String handlerName, Consumer<QQRelationshipEvent> listener) {
        relationshipListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered relationship handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步关系事件监听器。 */
    public QQBot onRelationshipAsync(String handlerName,
                                     Function<QQRelationshipEvent, ? extends CompletionStage<?>> listener) {
        relationshipListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async relationship handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步消息状态监听器。 */
    public QQBot onMessageStatus(Consumer<QQMessageStatusEvent> listener) {
        return onMessageStatus(autoName(listener), listener);
    }

    /** 注册指定名称的同步消息状态监听器。 */
    public QQBot onMessageStatus(String handlerName, Consumer<QQMessageStatusEvent> listener) {
        messageStatusListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered message-status handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步消息状态监听器。 */
    public QQBot onMessageStatusAsync(String handlerName,
                                      Function<QQMessageStatusEvent, ? extends CompletionStage<?>> listener) {
        messageStatusListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async message-status handler=" + handlerName);
        return this;
    }

    /** 注册未命名的同步资源监听器。 */
    public QQBot onResource(Consumer<QQResourceEvent> listener) {
        return onResource(autoName(listener), listener);
    }

    /** 注册指定名称的同步资源监听器。 */
    public QQBot onResource(String handlerName, Consumer<QQResourceEvent> listener) {
        resourceListeners.add(syncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered resource handler=" + handlerName);
        return this;
    }

    /** 注册指定名称的异步资源监听器。 */
    public QQBot onResourceAsync(String handlerName,
                                 Function<QQResourceEvent, ? extends CompletionStage<?>> listener) {
        resourceListeners.add(asyncHandler(handlerName, listener));
        logger.debug("PLUGIN", "registered async resource handler=" + handlerName);
        return this;
    }

    private QQBot onCommand(String handlerName, String command, int priority,
                            boolean continuePropagation, Consumer<QQCommandEvent> listener) {
        commandListeners.add(new CommandHandler(requireHandlerName(handlerName), normalizeCommand(command),
                priority, continuePropagation, syncAction(listener)));
        logger.debug("PLUGIN", "registered command handler=" + handlerName + " command=" + command
                + " priority=" + priority + " continue=" + continuePropagation);
        return this;
    }

    private QQBot onCommandAsync(String handlerName, String command, int priority,
                                 boolean continuePropagation,
                                 Function<QQCommandEvent, ? extends CompletionStage<?>> listener) {
        commandListeners.add(new CommandHandler(requireHandlerName(handlerName), normalizeCommand(command),
                priority, continuePropagation, asyncAction(listener)));
        logger.debug("PLUGIN", "registered async command handler=" + handlerName + " command=" + command
                + " priority=" + priority + " continue=" + continuePropagation);
        return this;
    }

    /** 注册未命名的错误监听器。 */
    public QQBot onError(Consumer<Throwable> listener) {
        return onError(autoName(listener), listener);
    }

    /** 注册指定名称的错误监听器。 */
    public QQBot onError(String handlerName, Consumer<Throwable> listener) {
        Objects.requireNonNull(listener, "listener");
        errorListeners.add(new NamedErrorHandler(requireHandlerName(handlerName),
                error -> {
                    listener.accept(error);
                    return CompletableFuture.completedFuture(null);
                }));
        logger.debug("PLUGIN", "registered error handler=" + handlerName);
        return this;
    }

    private QQBot onErrorAsync(String handlerName,
                               Function<Throwable, ? extends CompletionStage<?>> listener) {
        errorListeners.add(new NamedErrorHandler(requireHandlerName(handlerName),
                Objects.requireNonNull(listener, "listener")));
        logger.debug("PLUGIN", "registered async error handler=" + handlerName);
        return this;
    }

    /** Listener registration scope for one plugin. */
    public static final class Plugin {
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
        public Plugin onEvent(Consumer<QQEvent> listener) {
            String name = handler("event");
            bot.onEventAsync(name, runtime.sync(name, "event", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的原始事件监听器。 */
        public Plugin onEventAsync(Function<QQEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("event");
            bot.onEventAsync(name, runtime.async(name, "event", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的指定类型事件监听器。 */
        public Plugin onEvent(String type, Consumer<QQEvent> listener) {
            String name = handler("event." + type);
            bot.onEventAsync(type, name, runtime.sync(name, "typed-event", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的指定类型事件监听器。 */
        public Plugin onEventAsync(String type, Function<QQEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("event." + type);
            bot.onEventAsync(type, name, runtime.async(name, "typed-event", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的消息监听器。 */
        public Plugin onMessage(Consumer<QQMessageEvent> listener) {
            String name = handler("message");
            bot.onMessageAsync(name, runtime.sync(name, "message", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的消息监听器。 */
        public Plugin onMessageAsync(Function<QQMessageEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("message");
            bot.onMessageAsync(name, runtime.async(name, "message", listener));
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
                                Consumer<QQCommandEvent> listener) {
            String name = handler("command." + command);
            bot.onCommandAsync(name, command, priority, continuePropagation,
                    runtime.sync(name, "command", listener));
            return this;
        }

        /**
         * 注册在当前插件执行器中启动并跟踪 Stage 的带优先级命令监听器。
         *
         * @param command 要匹配的命令名，不包含前缀
         * @param priority 优先级，数值越大越先执行
         * @param continuePropagation 是否允许继续执行下一个匹配监听器
         * @param listener 命令处理函数，返回的 Stage 完成后才继续传播
         * @return 当前插件作用域
         */
        public Plugin onCommandAsync(String command, int priority, boolean continuePropagation,
                                     Function<QQCommandEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("command." + command);
            bot.onCommandAsync(name, command, priority, continuePropagation,
                    runtime.async(name, "command", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的互动监听器。 */
        public Plugin onInteraction(Consumer<QQInteractionEvent> listener) {
            String name = handler("interaction");
            bot.onInteractionAsync(name, runtime.sync(name, "interaction", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的互动监听器。 */
        public Plugin onInteractionAsync(Function<QQInteractionEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("interaction");
            bot.onInteractionAsync(name, runtime.async(name, "interaction", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的关系事件监听器。 */
        public Plugin onRelationship(Consumer<QQRelationshipEvent> listener) {
            String name = handler("relationship");
            bot.onRelationshipAsync(name, runtime.sync(name, "relationship", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的关系事件监听器。 */
        public Plugin onRelationshipAsync(Function<QQRelationshipEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("relationship");
            bot.onRelationshipAsync(name, runtime.async(name, "relationship", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的消息状态监听器。 */
        public Plugin onMessageStatus(Consumer<QQMessageStatusEvent> listener) {
            String name = handler("message-status");
            bot.onMessageStatusAsync(name, runtime.sync(name, "message-status", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的消息状态监听器。 */
        public Plugin onMessageStatusAsync(Function<QQMessageStatusEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("message-status");
            bot.onMessageStatusAsync(name, runtime.async(name, "message-status", listener));
            return this;
        }

        /** 注册在当前插件执行器中运行的资源监听器。 */
        public Plugin onResource(Consumer<QQResourceEvent> listener) {
            String name = handler("resource");
            bot.onResourceAsync(name, runtime.sync(name, "resource", listener));
            return this;
        }

        /** 注册在当前插件执行器中启动并跟踪 Stage 的资源监听器。 */
        public Plugin onResourceAsync(Function<QQResourceEvent, ? extends CompletionStage<?>> listener) {
            String name = handler("resource");
            bot.onResourceAsync(name, runtime.async(name, "resource", listener));
            return this;
        }

        /** 在当前插件作用域注册错误监听器。 */
        public Plugin onError(Consumer<Throwable> listener) {
            String name = handler("error");
            bot.onErrorAsync(name, runtime.sync(name, "error", listener));
            return this;
        }

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
    public CompletableFuture<MessageResponse> sendPrivateMessage(String userOpenId, String content) {
        return sendPrivateMessage(userOpenId, MessagePayload.text(content));
    }

    /** 发送已构造的私聊消息载荷。 */
    public CompletableFuture<MessageResponse> sendPrivateMessage(String userOpenId, MessagePayload payload) {
        requireId(userOpenId, "userOpenId");
        return sendMessage("/v2/users/" + encodePathSegment(userOpenId) + "/messages", payload);
    }

    /** 发送群聊文本消息。 */
    public CompletableFuture<MessageResponse> sendGroupMessage(String groupOpenId, String content) {
        return sendGroupMessage(groupOpenId, MessagePayload.text(content));
    }

    /** 发送已构造的群聊消息载荷。 */
    public CompletableFuture<MessageResponse> sendGroupMessage(String groupOpenId, MessagePayload payload) {
        requireId(groupOpenId, "groupOpenId");
        return sendMessage("/v2/groups/" + encodePathSegment(groupOpenId) + "/messages", payload);
    }

    /** 发送文字子频道文本消息。 */
    public CompletableFuture<MessageResponse> sendChannelMessage(String channelId, String content) {
        return sendChannelMessage(channelId, MessagePayload.text(content));
    }

    /** 发送已构造的文字子频道消息载荷。 */
    public CompletableFuture<MessageResponse> sendChannelMessage(String channelId, MessagePayload payload) {
        requireId(channelId, "channelId");
        return sendChannelStyleMessage("/channels/" + encodePathSegment(channelId) + "/messages", payload);
    }

    /** 使用 multipart/form-data 向文字子频道直接上传并发送图片。 */
    public CompletableFuture<MessageResponse> sendChannelImage(String channelId, MessagePayload payload,
                                                                String fileName, String contentType, byte[] image) {
        requireId(channelId, "channelId");
        return sendChannelStyleImage("/channels/" + encodePathSegment(channelId) + "/messages",
                payload, fileName, contentType, image);
    }

    /** 发送频道私信文本消息，其中 {@code guildId} 是创建会话或私信事件返回的私信 Guild ID。 */
    public CompletableFuture<MessageResponse> sendDirectMessage(String guildId, String content) {
        return sendDirectMessage(guildId, MessagePayload.text(content));
    }

    /** 发送已构造的频道私信消息载荷。 */
    public CompletableFuture<MessageResponse> sendDirectMessage(String guildId, MessagePayload payload) {
        requireId(guildId, "guildId");
        return sendChannelStyleMessage("/dms/" + encodePathSegment(guildId) + "/messages", payload);
    }

    /** 使用 multipart/form-data 向频道私信会话直接上传并发送图片。 */
    public CompletableFuture<MessageResponse> sendDirectImage(String guildId, MessagePayload payload,
                                                               String fileName, String contentType, byte[] image) {
        requireId(guildId, "guildId");
        return sendChannelStyleImage("/dms/" + encodePathSegment(guildId) + "/messages",
                payload, fileName, contentType, image);
    }

    /** 回复标准化消息事件，并发送文本内容。 */
    public CompletableFuture<MessageResponse> replyText(QQMessageEvent event, String content) {
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
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
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

    private CompletionStage<Void> invokeCommandHandler(QQEvent event, CommandHandler handler,
                                                       QQCommandEvent commandEvent) {
        long started = System.nanoTime();
        logger.debug("PLUGIN", "dispatching handler=" + handler.name() + " kind=command command="
                + handler.command() + " eventType=" + event.type() + " eventId=" + event.id()
                + " priority=" + handler.priority());
        try {
            CompletionStage<?> completion = handler.action().apply(commandEvent);
            if (completion == null) {
                logHandlerCompleted("command", event, handler.name(), started);
                return CompletableFuture.completedFuture(null);
            }
            return completion.handle((ignored, error) -> {
                if (error == null) {
                    logHandlerCompleted("command", event, handler.name(), started);
                } else {
                    logger.error("PLUGIN", "failed handler=" + handler.name() + " kind=command"
                            + " command=" + handler.command() + " eventType=" + event.type()
                            + " eventId=" + event.id() + " elapsedMs=" + elapsedMillis(started), unwrap(error));
                }
                return null;
            });
        } catch (Throwable error) {
            logger.error("PLUGIN", "failed handler=" + handler.name() + " kind=command"
                    + " command=" + handler.command() + " eventType=" + event.type()
                    + " eventId=" + event.id() + " elapsedMs=" + elapsedMillis(started), error);
            return CompletableFuture.completedFuture(null);
        }
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
                CompletionStage<?> completion = listener.action().apply(actual);
                if (completion != null) {
                    completion.whenComplete((ignored, listenerError) -> {
                        if (listenerError != null) {
                            logger.error("PLUGIN", "error handler failed name=" + listener.name(),
                                    unwrap(listenerError));
                        }
                    });
                }
            } catch (Throwable listenerError) {
                logger.error("PLUGIN", "error handler failed name=" + listener.name(), listenerError);
            }
        }
    }

    private CompletableFuture<MessageResponse> sendMessage(String path, MessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        ObjectNode body = payload.copyNode();
        return api.postAsync(path, body).thenApply(QQBot::messageResponse);
    }

    private CompletableFuture<MessageResponse> sendChannelStyleMessage(String path, MessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        return api.postAsync(path, payload.copyChannelNode()).thenApply(QQBot::messageResponse);
    }

    private CompletableFuture<MessageResponse> sendChannelStyleImage(String path, MessagePayload payload,
                                                                      String fileName, String contentType,
                                                                      byte[] image) {
        Objects.requireNonNull(payload, "payload");
        return api.postMultipartAsync(path, payload.copyChannelNode(), fileName, contentType, image)
                .thenApply(QQBot::messageResponse);
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
        long started = System.nanoTime();
        try {
            CompletionStage<?> completion = handler.action().apply(value);
            if (completion == null) {
                logHandlerCompleted(kind, event, handler.name(), started);
                return;
            }
            completion.whenComplete((ignored, error) -> {
                if (error == null) {
                    logHandlerCompleted(kind, event, handler.name(), started);
                } else {
                    Throwable actual = unwrap(error);
                    logger.error("PLUGIN", "failed handler=" + handler.name() + " kind=" + kind
                            + " eventType=" + event.type() + " eventId=" + event.id()
                            + " elapsedMs=" + elapsedMillis(started), actual);
                }
            });
        } catch (Throwable error) {
            logger.error("PLUGIN", "failed handler=" + handler.name() + " kind=" + kind
                    + " eventType=" + event.type() + " eventId=" + event.id()
                    + " elapsedMs=" + elapsedMillis(started), error);
        }
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

    private static Function<QQCommandEvent, ? extends CompletionStage<?>> syncAction(
            Consumer<QQCommandEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        return value -> {
            listener.accept(value);
            return CompletableFuture.completedFuture(null);
        };
    }

    private static Function<QQCommandEvent, ? extends CompletionStage<?>> asyncAction(
            Function<QQCommandEvent, ? extends CompletionStage<?>> listener) {
        return Objects.requireNonNull(listener, "listener");
    }

    private static <T> NamedHandler<T> syncHandler(String name, Consumer<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return new NamedHandler<>(requireHandlerName(name), value -> {
            listener.accept(value);
            return CompletableFuture.completedFuture(null);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> NamedHandler<T> asyncHandler(String name,
                                                     Function<T, ? extends CompletionStage<?>> listener) {
        Objects.requireNonNull(listener, "listener");
        return new NamedHandler<>(requireHandlerName(name), value -> listener.apply(value));
    }

    private static final class PluginRuntime {
        private final QQBot bot;
        private final String name;
        private final PluginExecutionOptions options;
        private final Map<String, AtomicInteger> handlerCounts = new ConcurrentHashMap<>();
        private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
        private final Object completionMonitor = new Object();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final ThreadPoolExecutor executor;

        private PluginRuntime(QQBot bot, String name, PluginExecutionOptions options) {
            this.bot = bot;
            this.name = name;
            this.options = options;
            AtomicInteger workerId = new AtomicInteger();
            String threadName = sanitizeThreadName(name);
            this.executor = new ThreadPoolExecutor(
                    options.threads(),
                    options.threads(),
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(options.queueCapacity()),
                    task -> {
                        Thread thread = new Thread(task, "astraqqbot-plugin-" + bot.config.shardId()
                                + "-" + threadName + "-" + workerId.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }

        private String name() {
            return name;
        }

        private PluginExecutionOptions options() {
            return options;
        }

        private String nextHandler(String kind) {
            int occurrence = handlerCounts
                    .computeIfAbsent(kind, ignored -> new AtomicInteger())
                    .incrementAndGet();
            return name + "." + kind + (occurrence == 1 ? "" : "#" + occurrence);
        }

        private <T> Function<T, ? extends CompletionStage<?>> sync(
                String handlerName, String kind, Consumer<T> listener) {
            Objects.requireNonNull(listener, "listener");
            return value -> submit(handlerName, kind, value, current -> {
                listener.accept(current);
                return CompletableFuture.completedFuture(null);
            });
        }

        private <T> Function<T, ? extends CompletionStage<?>> async(
                String handlerName, String kind,
                Function<T, ? extends CompletionStage<?>> listener) {
            Objects.requireNonNull(listener, "listener");
            return value -> submit(handlerName, kind, value, listener);
        }

        private <T> CompletionStage<?> submit(String handlerName, String kind, T value,
                                               Function<T, ? extends CompletionStage<?>> action) {
            CompletableFuture<Object> result = new CompletableFuture<>();
            inFlight.add(result);
            result.whenComplete((ignored, error) -> {
                inFlight.remove(result);
                synchronized (completionMonitor) {
                    completionMonitor.notifyAll();
                }
            });
            PluginTask task = new PluginTask(result, () -> {
                try {
                    CompletionStage<?> completion = action.apply(value);
                    if (completion == null) {
                        result.complete(null);
                    } else {
                        completion.whenComplete((ignored, error) -> {
                            if (error == null) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(unwrap(error));
                            }
                        });
                    }
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            try {
                if (closing.get()) {
                    throw new RejectedExecutionException("plugin executor is closing");
                }
                executor.execute(task);
                try {
                    bot.logger.debug("PLUGIN", "captured handler=" + handlerName + " kind=" + kind
                            + " plugin=" + name + " eventType=" + eventType(value)
                            + " eventId=" + eventId(value) + " queueSize=" + executor.getQueue().size());
                } finally {
                    task.accepted();
                }
            } catch (RejectedExecutionException rejected) {
                RejectedExecutionException detailed = new RejectedExecutionException(
                        "Plugin '" + name + "' rejected handler '" + handlerName + "'"
                                + " eventType=" + eventType(value) + " eventId=" + eventId(value)
                                + " threads=" + options.threads()
                                + " queue=" + executor.getQueue().size() + "/" + options.queueCapacity(),
                        rejected);
                result.completeExceptionally(detailed);
                if (!"error".equals(kind)) {
                    bot.reportError("PLUGIN", detailed);
                }
            }
            return result;
        }

        private void beginShutdown() {
            if (closing.compareAndSet(false, true)) {
                executor.shutdown();
            }
        }

        private void awaitShutdown(long deadline, Duration configuredTimeout) {
            boolean interrupted = false;
            synchronized (completionMonitor) {
                while (!inFlight.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(completionMonitor, remaining);
                    } catch (InterruptedException error) {
                        interrupted = true;
                        break;
                    }
                }
            }
            if (inFlight.isEmpty() && !interrupted) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    try {
                        executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
            }
            if (!executor.isTerminated() || !inFlight.isEmpty()) {
                int cancelled = inFlight.size();
                CancellationException cancellation = new CancellationException(
                        "Plugin '" + name + "' did not stop within " + configuredTimeout);
                for (Runnable queued : executor.shutdownNow()) {
                    if (queued instanceof PluginTask task) {
                        task.cancel(cancellation);
                    }
                }
                for (CompletableFuture<?> future : List.copyOf(inFlight)) {
                    future.completeExceptionally(cancellation);
                }
                bot.logger.warn("PLUGIN", "shutdown timed out plugin=" + name
                        + " cancelled=" + cancelled);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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

        private static final class PluginTask implements Runnable {
            private final CompletableFuture<?> result;
            private final Runnable action;
            private final CountDownLatch accepted = new CountDownLatch(1);

            private PluginTask(CompletableFuture<?> result, Runnable action) {
                this.result = result;
                this.action = action;
            }

            @Override
            public void run() {
                try {
                    accepted.await();
                    if (!result.isDone()) {
                        action.run();
                    }
                } catch (InterruptedException error) {
                    result.completeExceptionally(error);
                    Thread.currentThread().interrupt();
                }
            }

            private void accepted() {
                accepted.countDown();
            }

            private void cancel(Throwable error) {
                result.completeExceptionally(error);
            }
        }
    }

    private record NamedHandler<T>(String name, Function<T, ? extends CompletionStage<?>> action) {
    }

    private record CommandHandler(String name, String command, int priority, boolean continuePropagation,
                                  Function<QQCommandEvent, ? extends CompletionStage<?>> action) {
    }

    private record ParsedCommand(String prefix, String command, List<String> tokens) {
    }

    private record NamedErrorHandler(String name,
                                     Function<Throwable, ? extends CompletionStage<?>> action) {
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
        List<PluginRuntime> runtimes = List.copyOf(pluginRuntimes.values());
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
