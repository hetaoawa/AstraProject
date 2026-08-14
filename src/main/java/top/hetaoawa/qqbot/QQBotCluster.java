package top.hetaoawa.qqbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Creates and manages one {@link QQBot} instance per Gateway shard. */
/** Manages one {@link QQBot} instance per Gateway shard. */
public final class QQBotCluster implements AutoCloseable {
    private final List<QQBot> bots;

    private QQBotCluster(BotConfig baseConfig, int shardCount) {
        Objects.requireNonNull(baseConfig, "baseConfig");
        if (shardCount < 1) throw new IllegalArgumentException("shardCount must be positive");
        List<QQBot> instances = new ArrayList<>(shardCount);
        for (int shardId = 0; shardId < shardCount; shardId++) {
            instances.add(QQBot.create(baseConfig.toBuilder().shard(shardId, shardCount).build()));
        }
        bots = List.copyOf(instances);
    }

    /** Creates a cluster using the supplied base configuration and shard count. */
    public static QQBotCluster create(BotConfig baseConfig, int shardCount) {
        return new QQBotCluster(baseConfig, shardCount);
    }

    /** Returns the immutable list of shard bot instances. */
    public List<QQBot> bots() { return bots; }

    /** Registers one raw-event listener on every shard. */
    public QQBotCluster onEvent(Consumer<QQEvent> listener) { bots.forEach(bot -> bot.onEvent(listener)); return this; }
    /** Registers one message listener on every shard. */
    public QQBotCluster onMessage(Consumer<QQMessageEvent> listener) { bots.forEach(bot -> bot.onMessage(listener)); return this; }
    /** Registers one interaction listener on every shard. */
    public QQBotCluster onInteraction(Consumer<QQInteractionEvent> listener) { bots.forEach(bot -> bot.onInteraction(listener)); return this; }
    /** Registers one relationship listener on every shard. */
    public QQBotCluster onRelationship(Consumer<QQRelationshipEvent> listener) { bots.forEach(bot -> bot.onRelationship(listener)); return this; }
    /** Registers one message-status listener on every shard. */
    public QQBotCluster onMessageStatus(Consumer<QQMessageStatusEvent> listener) { bots.forEach(bot -> bot.onMessageStatus(listener)); return this; }
    /** Registers one resource listener on every shard. */
    public QQBotCluster onResource(Consumer<QQResourceEvent> listener) { bots.forEach(bot -> bot.onResource(listener)); return this; }
    /** Registers one unnamed error listener on every shard. */
    public QQBotCluster onError(Consumer<Throwable> listener) { bots.forEach(bot -> bot.onError(listener)); return this; }
    /** Registers one named error listener on every shard. */
    public QQBotCluster onError(String handlerName, Consumer<Throwable> listener) {
        bots.forEach(bot -> bot.onError(handlerName, listener));
        return this;
    }

    /** Starts all shard Gateway clients and completes when all shards are READY. */
    public CompletableFuture<Void> startWebSocket() {
        return CompletableFuture.allOf(bots.stream().map(QQBot::startWebSocket).toArray(CompletableFuture[]::new));
    }

    /** Closes every shard bot. */
    @Override
    public void close() { bots.forEach(QQBot::close); }
}
