package top.hetaoawa.qqbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 为每个 Gateway 分片创建并管理一个 {@link QQBot} 实例。 */
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

    /** 使用基础配置和分片数量创建集群。 */
    public static QQBotCluster create(BotConfig baseConfig, int shardCount) {
        return new QQBotCluster(baseConfig, shardCount);
    }

    /** 返回不可变的分片 Bot 实例列表。 */
    public List<QQBot> bots() { return bots; }

    /** 在每个分片注册原始事件监听器。 */
    public QQBotCluster onEvent(Consumer<QQEvent> listener) { bots.forEach(bot -> bot.onEvent(listener)); return this; }
    /** 在每个分片注册消息监听器。 */
    public QQBotCluster onMessage(Consumer<QQMessageEvent> listener) { bots.forEach(bot -> bot.onMessage(listener)); return this; }
    /** 在每个分片注册互动监听器。 */
    public QQBotCluster onInteraction(Consumer<QQInteractionEvent> listener) { bots.forEach(bot -> bot.onInteraction(listener)); return this; }
    /** 在每个分片注册关系事件监听器。 */
    public QQBotCluster onRelationship(Consumer<QQRelationshipEvent> listener) { bots.forEach(bot -> bot.onRelationship(listener)); return this; }
    /** 在每个分片注册消息状态监听器。 */
    public QQBotCluster onMessageStatus(Consumer<QQMessageStatusEvent> listener) { bots.forEach(bot -> bot.onMessageStatus(listener)); return this; }
    /** 在每个分片注册资源监听器。 */
    public QQBotCluster onResource(Consumer<QQResourceEvent> listener) { bots.forEach(bot -> bot.onResource(listener)); return this; }
    /** 在每个分片注册未命名错误监听器。 */
    public QQBotCluster onError(Consumer<Throwable> listener) { bots.forEach(bot -> bot.onError(listener)); return this; }
    /** 在每个分片注册指定名称的错误监听器。 */
    public QQBotCluster onError(String handlerName, Consumer<Throwable> listener) {
        bots.forEach(bot -> bot.onError(handlerName, listener));
        return this;
    }

    /** 启动所有分片 Gateway，并在所有分片 READY 后完成 Future。 */
    public CompletableFuture<Void> startWebSocket() {
        return CompletableFuture.allOf(bots.stream().map(QQBot::startWebSocket).toArray(CompletableFuture[]::new));
    }

    /** 关闭所有分片 Bot。 */
    @Override
    public void close() { bots.forEach(QQBot::close); }
}
