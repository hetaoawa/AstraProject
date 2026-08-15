package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QQExtendedEventTest {
    @Test
    void dispatchesNormalizedInteractionRelationshipStatusAndResourceEvents() {
        QQBot bot = QQBot.create(BotConfig.builder().appId("app").clientSecret("secret")
                .logLevel(BotLogLevel.OFF).build());
        AtomicReference<QQInteractionEvent> interaction = new AtomicReference<>();
        AtomicReference<QQRelationshipEvent> relationship = new AtomicReference<>();
        AtomicReference<QQMessageStatusEvent> status = new AtomicReference<>();
        AtomicReference<QQResourceEvent> resource = new AtomicReference<>();
        bot.onInteraction(interaction::set)
                .onRelationship(relationship::set)
                .onMessageStatus(status::set)
                .onResource(resource::set);

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

        assertEquals("interaction-1", interaction.get().interactionId());
        assertEquals("user-1", relationship.get().userOpenId());
        assertEquals("message-1", status.get().messageId());
        assertEquals("channel-1", resource.get().resourceId());
        assertNotNull(resource.get().data());
        bot.close();
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
