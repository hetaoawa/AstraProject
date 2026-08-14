package top.hetaoawa.qqbot;

import java.net.URI;
import java.time.Duration;
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

    public static Builder builder() {
        return new Builder();
    }

    public String appId() {
        return appId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public URI apiBaseUri() {
        return apiBaseUri;
    }

    public URI accessTokenUri() {
        return accessTokenUri;
    }

    public long intents() {
        return intents;
    }

    public int shardId() {
        return shardId;
    }

    public int shardCount() {
        return shardCount;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration reconnectInitialDelay() {
        return reconnectInitialDelay;
    }

    public Duration reconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public String webhookHost() {
        return webhookHost;
    }

    public int webhookPort() {
        return webhookPort;
    }

    public String webhookPath() {
        return webhookPath;
    }

    public String userAgent() {
        return userAgent;
    }

    public BotLogLevel logLevel() {
        return logLevel;
    }

    public boolean logEventPayloads() {
        return logEventPayloads;
    }

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
                .logEventPayloads(logEventPayloads);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder clientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
            return this;
        }

        public Builder apiBaseUri(String apiBaseUri) {
            return apiBaseUri(URI.create(apiBaseUri));
        }

        public Builder apiBaseUri(URI apiBaseUri) {
            this.apiBaseUri = apiBaseUri;
            return this;
        }

        public Builder accessTokenUri(String accessTokenUri) {
            return accessTokenUri(URI.create(accessTokenUri));
        }

        public Builder accessTokenUri(URI accessTokenUri) {
            this.accessTokenUri = accessTokenUri;
            return this;
        }

        public Builder intents(long intents) {
            this.intents = intents;
            return this;
        }

        public Builder shard(int shardId, int shardCount) {
            this.shardId = shardId;
            this.shardCount = shardCount;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder reconnectInitialDelay(Duration reconnectInitialDelay) {
            this.reconnectInitialDelay = reconnectInitialDelay;
            return this;
        }

        public Builder reconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
            return this;
        }

        public Builder webhookAddress(String host, int port) {
            this.webhookHost = host;
            this.webhookPort = port;
            return this;
        }

        public Builder webhookPath(String webhookPath) {
            this.webhookPath = webhookPath;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder logLevel(BotLogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /** Enables raw event data at TRACE level. Event payloads may contain user content. */
        public Builder logEventPayloads(boolean logEventPayloads) {
            this.logEventPayloads = logEventPayloads;
            return this;
        }

        public BotConfig build() {
            return new BotConfig(this);
        }
    }
}
