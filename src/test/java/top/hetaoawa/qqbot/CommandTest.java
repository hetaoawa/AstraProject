package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandTest {
    @Test
    void parsesConfiguredPrefixAndArgumentsAndOrdersByPriority() {
        BotConfig config = BotConfig.builder()
                .appId("app").clientSecret("secret")
                .commandPrefixes("/", "#", ".")
                .commandSeparator(" ")
                .logLevel(BotLogLevel.OFF)
                .build();
        List<String> calls = new ArrayList<>();
        try (QQBot bot = QQBot.create(config)) {
            QQBot.Plugin plugin = bot.plugin("moderation");
            plugin.onCommand("hello", 100, true, command -> {
                calls.add(command.prefix() + ":" + command.command() + ":" + command.arguments());
            });
            plugin.onCommand("hello", 10, false, command -> calls.add("second"));
            plugin.onCommand("hello", 0, true, command -> calls.add("blocked"));

            var data = JsonNodeFactory.instance.objectNode()
                    .put("id", "message-1")
                    .put("content", "#hello one   two");
            data.putObject("author").put("user_openid", "user-1");
            bot.dispatch(new QQEvent("event-1", 0, 1L, "C2C_MESSAGE_CREATE", data, data));

            assertEquals(List.of("#:hello:[one, two]", "second"), calls);
        }
    }

    @Test
    void ignoresMessagesWithoutConfiguredCommand() {
        BotConfig config = BotConfig.builder()
                .appId("app").clientSecret("secret")
                .commandPrefixes("/")
                .logLevel(BotLogLevel.OFF)
                .build();
        List<String> calls = new ArrayList<>();
        try (QQBot bot = QQBot.create(config)) {
            bot.plugin("help").onCommand("help", 1, false, command -> calls.add(command.command()));
            var data = JsonNodeFactory.instance.objectNode()
                    .put("id", "message-1")
                    .put("content", "hello");
            data.putObject("author").put("user_openid", "user-1");
            bot.dispatch(new QQEvent("event-1", 0, 1L, "C2C_MESSAGE_CREATE", data, data));
            assertEquals(List.of(), calls);
        }
    }
}
