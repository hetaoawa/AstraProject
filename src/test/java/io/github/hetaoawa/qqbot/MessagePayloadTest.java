package io.github.hetaoawa.qqbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagePayloadTest {
    @Test
    void createsTextPayload() {
        var json = MessagePayload.text("hello").toJson();
        assertEquals(0, json.path("msg_type").asInt());
        assertEquals("hello", json.path("content").asText());
    }

    @Test
    void addsReplyFields() {
        var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        data.put("id", "message-1");
        data.putObject("author").put("user_openid", "user-1");
        var event = new QQEvent("event-1", 0, 1L, "C2C_MESSAGE_CREATE", data, data);
        var bot = QQBot.create(BotConfig.builder().appId("app").clientSecret("secret").build());
        var message = QQMessageEvent.from(bot, event);
        var json = MessagePayload.text("reply").replyTo(message).toJson();
        assertEquals("message-1", json.path("msg_id").asText());
        assertEquals(1, json.path("msg_seq").asInt());
        bot.close();
    }
}
