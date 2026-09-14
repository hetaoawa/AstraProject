package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQExtendedEventTest {
    @Test
    void dispatchesNormalizedInteractionRelationshipStatusAndResourceEvents() throws Exception {
        try (QQBot bot = QQBot.create(BotConfig.builder().appId("app").clientSecret("secret")
                .logLevel(BotLogLevel.OFF).build());
        ) {
        AtomicReference<QQInteractionEvent> interaction = new AtomicReference<>();
        AtomicReference<QQRelationshipEvent> relationship = new AtomicReference<>();
        AtomicReference<QQMessageStatusEvent> status = new AtomicReference<>();
        AtomicReference<QQResourceEvent> resource = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(4);
        bot.onInteraction(value -> { interaction.set(value); completed.countDown(); })
                .onRelationship(value -> { relationship.set(value); completed.countDown(); })
                .onMessageStatus(value -> { status.set(value); completed.countDown(); })
                .onResource(value -> { resource.set(value); completed.countDown(); });

        var interactionData = JsonNodeFactory.instance.objectNode()
                .put("id", "interaction-1").put("group_openid", "group-1").put("chat_type", 1);
        bot.dispatch(new QQEvent("event-1", 0, 1L, "INTERACTION_CREATE", interactionData, interactionData));

        var relationshipData = JsonNodeFactory.instance.objectNode()
                .put("user_openid", "user-1").put("group_openid", "group-1");
        bot.dispatch(new QQEvent("event-2", 0, 2L, "GROUP_MEMBER_ADD", relationshipData, relationshipData));

        var statusData = JsonNodeFactory.instance.objectNode()
                .put("message_id", "message-1").put("status", "delivered");
        bot.dispatch(new QQEvent("event-3", 0, 3L, "SUBSCRIBE_MESSAGE_STATUS", statusData, statusData));

        var resourceData = JsonNodeFactory.instance.objectNode()
                .put("id", "channel-1").put("guild_id", "guild-1");
        bot.dispatch(new QQEvent("event-4", 0, 4L, "CHANNEL_CREATE", resourceData, resourceData));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals("interaction-1", interaction.get().interactionId());
        assertEquals("user-1", relationship.get().userOpenId());
        assertEquals("message-1", status.get().messageId());
        assertEquals("channel-1", resource.get().resourceId());
        assertNotNull(resource.get().data());
        }
    }

    @Test
    void createsOneBotPerShard() {
        BotConfig base = BotConfig.builder().appId("app").clientSecret("secret")
                .logLevel(BotLogLevel.OFF).build();
        try (QQBotCluster cluster = QQBotCluster.create(base, 3)) {
            assertEquals(3, cluster.bots().size());
            assertEquals(0, cluster.bots().get(0).config().shardId());
            assertEquals(2, cluster.bots().get(2).config().shardId());
            assertEquals(3, cluster.bots().get(2).config().shardCount());
        }
    }
}
