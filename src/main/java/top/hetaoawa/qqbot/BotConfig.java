package top.hetaoawa.qqbot;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable configuration for a QQ official bot client. */
public final class BotConfig {
    private final String appId;
    private final String clientSecret;
    private final URI apiBaseUri;
    private final URI accessTokenUri;
    private final long intents;
    private final int shardId;
    private final int shardCount;
    private final Duration connectTimeout;
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
        if (reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("reconnectMaxDelay must not be smaller than reconnectInitialDelay");
        }
    }

    /** Creates a builder with the framework defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the QQ Open Platform application ID. */
    public String appId() {
        return appId;
    }

    /** Returns the application secret used for authentication. */
    public String clientSecret() {
        return clientSecret;
    }

    /** Returns the base URI used for OpenAPI requests. */
    public URI apiBaseUri() {
        return apiBaseUri;
    }

    /** Returns the URI used to obtain access tokens. */
    public URI accessTokenUri() {
        return accessTokenUri;
    }

    /** Returns the Gateway intent bit mask. */
    public long intents() {
        return intents;
    }

    /** Returns this bot instance's zero-based shard ID. */
    public int shardId() {
        return shardId;
    }

    /** Returns the total number of Gateway shards. */
    public int shardCount() {
        return shardCount;
    }

    /** Returns the HTTP and WebSocket connection timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the initial Gateway reconnect delay. */
    public Duration reconnectInitialDelay() {
        return reconnectInitialDelay;
    }

    /** Returns the maximum Gateway reconnect delay. */
    public Duration reconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    /** Returns the local Webhook bind address. */
    public String webhookHost() {
        return webhookHost;
    }

    /** Returns the local Webhook bind port. */
    public int webhookPort() {
        return webhookPort;
    }

    /** Returns the local Webhook request path. */
    public String webhookPath() {
        return webhookPath;
    }

    /** Returns the HTTP User-Agent value. */
    public String userAgent() {
        return userAgent;
    }

    /** Returns the framework console log threshold. */
    public BotLogLevel logLevel() {
        return logLevel;
    }

    /** Returns whether raw event payloads may be logged at TRACE level. */
    public boolean logEventPayloads() {
        return logEventPayloads;
    }

    /** Returns the configured command prefixes in matching order. */
    public List<String> commandPrefixes() {
        return commandPrefixes;
    }

    /** Returns the delimiter used to split command tokens. */
    public String commandSeparator() {
        return commandSeparator;
    }

    /** Creates a builder initialized from this configuration. */
    public Builder toBuilder() {
        return builder()
                .appId(appId)
                .clientSecret(clientSecret)
                .apiBaseUri(apiBaseUri)
                .accessTokenUri(accessTokenUri)
                .intents(intents)
                .shard(shardId, shardCount)
                .connectTimeout(connectTimeout)
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

    /** Mutable builder for {@link BotConfig}. */
    public static final class Builder {
        private String appId;
        private String clientSecret;
        private URI apiBaseUri = URI.create("https://api.bot.qq.com/");
        private URI accessTokenUri = URI.create("https://bots.qq.com/app/getAppAccessToken");
        private long intents = Intents.PRIVATE_AND_GROUP;
        private int shardId;
        private int shardCount = 1;
        private Duration connectTimeout = Duration.ofSeconds(20);
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

        /** Sets the required QQ application ID. */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /** Sets the required QQ application secret. */
        public Builder clientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
            return this;
        }

        /** Sets the OpenAPI base URI from a string. */
        public Builder apiBaseUri(String apiBaseUri) {
            return apiBaseUri(URI.create(apiBaseUri));
        }

        /** Sets the OpenAPI base URI. */
        public Builder apiBaseUri(URI apiBaseUri) {
            this.apiBaseUri = apiBaseUri;
            return this;
        }

        /** Sets the access-token URI from a string. */
        public Builder accessTokenUri(String accessTokenUri) {
            return accessTokenUri(URI.create(accessTokenUri));
        }

        /** Sets the access-token URI. */
        public Builder accessTokenUri(URI accessTokenUri) {
            this.accessTokenUri = accessTokenUri;
            return this;
        }

        /** Sets the Gateway intent bit mask. */
        public Builder intents(long intents) {
            this.intents = intents;
            return this;
        }

        /** Sets the current shard ID and total shard count. */
        public Builder shard(int shardId, int shardCount) {
            this.shardId = shardId;
            this.shardCount = shardCount;
            return this;
        }

        /** Sets the HTTP and WebSocket connection timeout. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /** Sets the initial Gateway reconnect delay. */
        public Builder reconnectInitialDelay(Duration reconnectInitialDelay) {
            this.reconnectInitialDelay = reconnectInitialDelay;
            return this;
        }

        /** Sets the maximum Gateway reconnect delay. */
        public Builder reconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
            return this;
        }

        /** Sets the local Webhook bind address and port. */
        public Builder webhookAddress(String host, int port) {
            this.webhookHost = host;
            this.webhookPort = port;
            return this;
        }

        /** Sets the local Webhook request path. */
        public Builder webhookPath(String webhookPath) {
            this.webhookPath = webhookPath;
            return this;
        }

        /** Sets the HTTP User-Agent value. */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** Sets the framework console log threshold. */
        public Builder logLevel(BotLogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /** Enables raw event data at TRACE level. Event payloads may contain user content. */
        public Builder logEventPayloads(boolean logEventPayloads) {
            this.logEventPayloads = logEventPayloads;
            return this;
        }

        /** Sets one or more command prefixes, such as {@code /}, {@code #}, or {@code .}. */
        public Builder commandPrefixes(String... commandPrefixes) {
            return commandPrefixes(Arrays.asList(commandPrefixes));
        }

        /** Sets the command prefixes from a collection. */
        public Builder commandPrefixes(List<String> commandPrefixes) {
            this.commandPrefixes = commandPrefixes;
            return this;
        }

        /** Sets the non-empty delimiter used to split command tokens. */
        public Builder commandSeparator(String commandSeparator) {
            this.commandSeparator = commandSeparator;
            return this;
        }

        /** Validates and creates the immutable configuration. */
        public BotConfig build() {
            return new BotConfig(this);
        }
    }
}
