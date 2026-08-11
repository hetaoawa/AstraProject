package io.github.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMessageEventTest {
    @Test
    void normalizesGroupMessageAndSceneExtensions() throws Exception {
        var raw = new ObjectMapper().readTree("""
                {
                  "id":"message-1",
                  "author":{"id":"member-1","username":"小明","member_openid":"member-openid","member_role":"member"},
                  "content":" hello ",
                  "group_openid":"group-openid",
                  "message_type":0,
                  "message_scene":{"source":"default","ext":["msg_idx=index-1","auth_token=token-1"]},
                  "attachments":[{"url":"https://example.com/a.jpg","content_type":"image/jpeg","width":100,"height":80}]
                }
                """);
        var envelope = new QQEvent("event-1", 0, 3L, "GROUP_AT_MESSAGE_CREATE", raw, raw);
        var bot = QQBot.create(BotConfig.builder().appId("app").clientSecret("secret").build());
        var message = QQMessageEvent.from(bot, envelope);
        assertEquals("group-openid", message.groupOpenId());
        assertEquals("member-openid", message.author().memberOpenId());
        assertEquals("index-1", message.scene().extension("msg_idx"));
        assertEquals(1, message.attachments().size());
        assertTrue(message.isGroupMessage());
        bot.close();
    }
}
