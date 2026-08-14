package top.hetaoawa.qqbot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void createsTemplateMediaArkAndKeyboardPayloads() {
        var template = MessagePayload.markdownTemplate("template-1", Map.of("name", List.of("Astra")))
                .keyboardTemplate("keyboard-1")
                .messageSequence(2)
                .toJson();
        assertEquals("template-1", template.path("markdown").path("custom_template_id").asText());
        assertEquals("Astra", template.path("markdown").path("params").path(0).path("values").path(0).asText());
        assertEquals("keyboard-1", template.path("keyboard").path("id").asText());
        assertEquals(2, template.path("msg_seq").asInt());

        assertEquals("file-info", MessagePayload.media("file-info").toJson()
                .path("media").path("file_info").asText());

        var kv = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        kv.addObject().put("key", "#PROMPT#").put("value", "hello");
        assertEquals(23, MessagePayload.ark(23, kv).toJson().path("ark").path("template_id").asInt());
    }
}
