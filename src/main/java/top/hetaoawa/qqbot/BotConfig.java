package top.hetaoawa.qqbot;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** QQ 官方机器人客户端的不可变配置。 */
public final class BotConfig {
    private final String appId;
    private final String clientSecret;
    private final URI apiBaseUri;
    private final URI accessTokenUri;
    private final long intents;
    private final int shardId;
    private final int shardCount;
    private final Duration connectTimeout;
    private final int httpExecutorThreads;
    private final int maxConcurrentTasks;
    private final int maxPendingTasks;
    private final Duration pluginTaskTimeout;
    private final Duration pluginShutdownTimeout;
    private final Duration reconnectInitialDelay;
    private final Duration reconnectMaxDelay;
    private final String webhookHost;
    private final int webhookPort;
    private final String webhookPath;
    private final String userAgent;
    private final BotLogLevel logLevel;
    private final boolean logEventPayloads;
    private final List<String> commandPrefixes;
    private final String commandSeparator;

    private BotConfig(Builder builder) {
        this.appId = requireText(builder.appId, "appId");
        this.clientSecret = requireText(builder.clientSecret, "clientSecret");
        this.apiBaseUri = Objects.requireNonNull(builder.apiBaseUri, "apiBaseUri");
        this.accessTokenUri = Objects.requireNonNull(builder.accessTokenUri, "accessTokenUri");
        this.intents = builder.intents;
        this.shardId = builder.shardId;
        this.shardCount = builder.shardCount;
        this.connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        this.httpExecutorThreads = builder.httpExecutorThreads;
        this.maxConcurrentTasks = builder.maxConcurrentTasks;
        this.maxPendingTasks = builder.maxPendingTasks;
        this.pluginTaskTimeout = positive(builder.pluginTaskTimeout, "pluginTaskTimeout");
        this.pluginShutdownTimeout = positive(builder.pluginShutdownTimeout, "pluginShutdownTimeout");
        this.reconnectInitialDelay = positive(builder.reconnectInitialDelay, "reconnectInitialDelay");
        this.reconnectMaxDelay = positive(builder.reconnectMaxDelay, "reconnectMaxDelay");
        this.webhookHost = requireText(builder.webhookHost, "webhookHost");
        this.webhookPort = builder.webhookPort;
        this.webhookPath = normalizePath(builder.webhookPath);
        this.userAgent = requireText(builder.userAgent, "userAgent");
        this.logLevel = Objects.requireNonNull(builder.logLevel, "logLevel");
        this.logEventPayloads = builder.logEventPayloads;
        this.commandPrefixes = normalizePrefixes(builder.commandPrefixes);
        this.commandSeparator = requireNonEmpty(builder.commandSeparator, "commandSeparator");

        if (shardCount < 1 || shardId < 0 || shardId >= shardCount) {
            throw new IllegalArgumentException("shardId must be in [0, shardCount), shardCount must be positive");
        }
        if (webhookPort < 1 || webhookPort > 65535) {
            throw new IllegalArgumentException("webhookPort must be between 1 and 65535");
        }
        if (httpExecutorThreads < 1) {
            throw new IllegalArgumentException("httpExecutorThreads must be positive");
        }
        if (maxConcurrentTasks < 1) {
            throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        }
        if (maxPendingTasks < 0) {
            throw new IllegalArgumentException("maxPendingTasks must not be negative");
        }
        if (reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("reconnectMaxDelay must not be smaller than reconnectInitialDelay");
        }
    }

    /** 创建一个带有框架默认值的配置构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 返回 QQ 开放平台 AppID。 */
    public String appId() {
        return appId;
    }

    /** 返回用于鉴权的 AppSecret。 */
    public String clientSecret() {
        return clientSecret;
    }

    /** 返回 OpenAPI 请求使用的基础 URI。 */
    public URI apiBaseUri() {
        return apiBaseUri;
    }

    /** 返回获取 Access Token 的 URI。 */
    public URI accessTokenUri() {
        return accessTokenUri;
    }

    /** 返回 Gateway Intent 位掩码。 */
    public long intents() {
        return intents;
    }

    /** 返回当前 Bot 实例的从零开始的分片编号。 */
    public int shardId() {
        return shardId;
    }

    /** 返回 Gateway 分片总数。 */
    public int shardCount() {
        return shardCount;
    }

    /** 返回 HTTP 和 WebSocket 建连超时时间。 */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** 返回执行阻塞 HTTP 请求的专用工作线程数。 */
    public int httpExecutorThreads() {
        return httpExecutorThreads;
    }

    /** 返回每个插件默认允许同时运行的回调数。 */
    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    /** 返回每个插件默认允许排队的任务数。 */
    public int maxPendingTasks() {
        return maxPendingTasks;
    }

    /** 返回单次插件回调的默认运行超时。 */
    public Duration pluginTaskTimeout() {
        return pluginTaskTimeout;
    }

    /** 返回关闭时等待插件任务完成的最长时间。 */
    public Duration pluginShutdownTimeout() {
        return pluginShutdownTimeout;
    }

    /** 返回 Gateway 首次重连等待时间。 */
    public Duration reconnectInitialDelay() {
        return reconnectInitialDelay;
    }

    /** 返回 Gateway 重连等待时间上限。 */
    public Duration reconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    /** 返回本地 Webhook 绑定地址。 */
    public String webhookHost() {
        return webhookHost;
    }

    /** 返回本地 Webhook 绑定端口。 */
    public int webhookPort() {
        return webhookPort;
    }

    /** 返回本地 Webhook 请求路径。 */
    public String webhookPath() {
        return webhookPath;
    }

    /** 返回 HTTP User-Agent 值。 */
    public String userAgent() {
        return userAgent;
    }

    /** 返回框架控制台日志级别阈值。 */
    public BotLogLevel logLevel() {
        return logLevel;
    }

    /** 返回是否允许在 TRACE 级别输出原始事件载荷。 */
    public boolean logEventPayloads() {
        return logEventPayloads;
    }

    /** 返回按匹配顺序排列的命令前缀。 */
    public List<String> commandPrefixes() {
        return commandPrefixes;
    }

    /** 返回拆分命令 Token 使用的分隔符。 */
    public String commandSeparator() {
        return commandSeparator;
    }

    /** 创建一个以当前配置初始化的构建器。 */
    public Builder toBuilder() {
        return builder()
                .appId(appId)
                .clientSecret(clientSecret)
                .apiBaseUri(apiBaseUri)
                .accessTokenUri(accessTokenUri)
                .intents(intents)
                .shard(shardId, shardCount)
                .connectTimeout(connectTimeout)
                .httpExecutorThreads(httpExecutorThreads)
                .maxConcurrentTasks(maxConcurrentTasks)
                .maxPendingTasks(maxPendingTasks)
                .pluginTaskTimeout(pluginTaskTimeout)
                .pluginShutdownTimeout(pluginShutdownTimeout)
                .reconnectInitialDelay(reconnectInitialDelay)
                .reconnectMaxDelay(reconnectMaxDelay)
                .webhookAddress(webhookHost, webhookPort)
                .webhookPath(webhookPath)
                .userAgent(userAgent)
                .logLevel(logLevel)
                .logEventPayloads(logEventPayloads)
                .commandPrefixes(commandPrefixes)
                .commandSeparator(commandSeparator);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String normalizePath(String value) {
        String path = requireText(value, "webhookPath");
        return path.startsWith("/") ? path : "/" + path;
    }

    private static List<String> normalizePrefixes(List<String> values) {
        Objects.requireNonNull(values, "commandPrefixes");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("commandPrefixes must not be empty");
        }
        List<String> prefixes = new ArrayList<>();
        for (String value : values) {
            String prefix = requireText(value, "commandPrefix");
            if (!prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }
        prefixes.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return List.copyOf(prefixes);
    }

    /** {@link BotConfig} 的可变构建器。 */
    public static final class Builder {
        private String appId;
        private String clientSecret;
        private URI apiBaseUri = URI.create("https://api.bot.qq.com/");
        private URI accessTokenUri = URI.create("https://bots.qq.com/app/getAppAccessToken");
        private long intents = Intents.PRIVATE_AND_GROUP;
        private int shardId;
        private int shardCount = 1;
        private Duration connectTimeout = Duration.ofSeconds(20);
        private int httpExecutorThreads = 4;
        private int maxConcurrentTasks = 256;
        private int maxPendingTasks = 512;
        private Duration pluginTaskTimeout = Duration.ofSeconds(60);
        private Duration pluginShutdownTimeout = Duration.ofSeconds(30);
        private Duration reconnectInitialDelay = Duration.ofSeconds(2);
        private Duration reconnectMaxDelay = Duration.ofSeconds(30);
        private String webhookHost = "127.0.0.1";
        private int webhookPort = 8080;
        private String webhookPath = "/qqbot/events";
        private String userAgent = "AstraQQBot/0.1.0";
        private BotLogLevel logLevel = BotLogLevel.INFO;
        private boolean logEventPayloads;
        private List<String> commandPrefixes = List.of("/");
        private String commandSeparator = " ";

        /** 设置必填的 QQ AppID。 */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /** 设置必填的 QQ AppSecret。 */
        public Builder clientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
            return this;
        }

        /** 使用字符串设置 OpenAPI 基础 URI。 */
        public Builder apiBaseUri(String apiBaseUri) {
            return apiBaseUri(URI.create(apiBaseUri));
        }

        /** 设置 OpenAPI 基础 URI。 */
        public Builder apiBaseUri(URI apiBaseUri) {
            this.apiBaseUri = apiBaseUri;
            return this;
        }

        /** 使用字符串设置 Access Token URI。 */
        public Builder accessTokenUri(String accessTokenUri) {
            return accessTokenUri(URI.create(accessTokenUri));
        }

        /** 设置 Access Token URI。 */
        public Builder accessTokenUri(URI accessTokenUri) {
            this.accessTokenUri = accessTokenUri;
            return this;
        }

        /** 设置 Gateway Intent 位掩码。 */
        public Builder intents(long intents) {
            this.intents = intents;
            return this;
        }

        /** 设置当前分片编号和分片总数。 */
        public Builder shard(int shardId, int shardCount) {
            this.shardId = shardId;
            this.shardCount = shardCount;
            return this;
        }

        /** 设置 HTTP 和 WebSocket 建连超时。 */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** 设置每个 Bot 用于阻塞 HTTP 请求的专用工作线程数。 */
        public Builder httpExecutorThreads(int httpExecutorThreads) {
            this.httpExecutorThreads = httpExecutorThreads;
            return this;
        }

        /** 设置每个插件默认允许同时运行的回调数。 */
        public Builder maxConcurrentTasks(int value) {
            this.maxConcurrentTasks = value;
            return this;
        }

        /** 设置每个插件默认允许排队的任务数。 */
        public Builder maxPendingTasks(int value) {
            this.maxPendingTasks = value;
            return this;
        }

        /** 设置单次插件回调的默认运行超时。 */
        public Builder pluginTaskTimeout(Duration value) {
            this.pluginTaskTimeout = value;
            return this;
        }

        /** 设置关闭时等待插件任务完成的最长时间。 */
        public Builder pluginShutdownTimeout(Duration pluginShutdownTimeout) {
            this.pluginShutdownTimeout = pluginShutdownTimeout;
            return this;
        }

        /** 设置 Gateway 首次重连延迟。 */
        public Builder reconnectInitialDelay(Duration reconnectInitialDelay) {
            this.reconnectInitialDelay = reconnectInitialDelay;
            return this;
        }

        /** 设置 Gateway 最大重连延迟。 */
        public Builder reconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
            return this;
        }

        /** 设置本地 Webhook 监听地址和端口。 */
        public Builder webhookAddress(String host, int port) {
            this.webhookHost = host;
            this.webhookPort = port;
            return this;
        }

        /** 设置本地 Webhook 请求路径。 */
        public Builder webhookPath(String webhookPath) {
            this.webhookPath = webhookPath;
            return this;
        }

        /** 设置 HTTP User-Agent 值。 */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** 设置框架控制台日志级别阈值。 */
        public Builder logLevel(BotLogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /** 开启 TRACE 级别原始事件输出；事件载荷可能包含用户内容。 */
        public Builder logEventPayloads(boolean logEventPayloads) {
            this.logEventPayloads = logEventPayloads;
            return this;
        }

        /**
         * 设置一个或多个命令前缀，例如 {@code /}、{@code #} 或 {@code .}。
         *
         * @param commandPrefixes 命令前缀数组，至少包含一个非空前缀
         * @return 当前构建器
         */
        public Builder commandPrefixes(String... commandPrefixes) {
            return commandPrefixes(Arrays.asList(commandPrefixes));
        }

        /** 从集合设置命令前缀。 */
        public Builder commandPrefixes(List<String> commandPrefixes) {
            this.commandPrefixes = commandPrefixes;
            return this;
        }

        /**
         * 设置用于拆分命令 Token 的非空分隔符。
         *
         * @param commandSeparator 分隔符，例如空格或逗号
         * @return 当前构建器
         */
        public Builder commandSeparator(String commandSeparator) {
            this.commandSeparator = commandSeparator;
            return this;
        }

        /** 校验并创建不可变配置。 */
        public BotConfig build() {
            return new BotConfig(this);
        }
    }
}
