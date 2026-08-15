package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingTest {
    @Test
    void debugLogsEventCaptureAndAsyncCompletion() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            BotConfig config = BotConfig.builder()
                    .appId("app").clientSecret("secret")
                    .logLevel(BotLogLevel.DEBUG)
                    .build();
            CompletableFuture<Void> pluginWork = new CompletableFuture<>();
            try (QQBot bot = QQBot.create(config)) {
                bot.plugin("hello-plugin").onMessageAsync(ignored -> pluginWork);
                var data = JsonNodeFactory.instance.objectNode()
                        .put("id", "message-1").put("content", "/hello");
                data.putObject("author").put("user_openid", "user-1");
                bot.dispatch(new QQEvent("event-1", 0, 1L, "C2C_MESSAGE_CREATE", data, data));

                String beforeCompletion = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(beforeCompletion.contains("received type=C2C_MESSAGE_CREATE id=event-1"));
                assertTrue(beforeCompletion.contains("captured handler=hello-plugin.message kind=message"));
                assertFalse(beforeCompletion.contains("completed handler=hello-plugin.message"));

                pluginWork.complete(null);
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("completed handler=hello-plugin.message"));
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void infoFiltersDebugAndTracePayloads() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            BotConfig config = BotConfig.builder()
                    .appId("app").clientSecret("secret")
                    .logLevel(BotLogLevel.INFO)
                    .logEventPayloads(true)
                    .build();
            try (QQBot bot = QQBot.create(config)) {
                var data = JsonNodeFactory.instance.objectNode().put("content", "private-content");
                bot.dispatch(new QQEvent("event-2", 0, 2L, "UNKNOWN", data, data));
            }
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("[INFO]"));
            assertFalse(output.contains("received type=UNKNOWN"));
            assertFalse(output.contains("private-content"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void traceCanIncludeEventPayloadWhenExplicitlyEnabled() {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            BotConfig config = BotConfig.builder()
                    .appId("app").clientSecret("secret")
                    .logLevel(BotLogLevel.TRACE)
                    .logEventPayloads(true)
                    .build();
            try (QQBot bot = QQBot.create(config)) {
                var data = JsonNodeFactory.instance.objectNode().put("content", "trace-content");
                bot.dispatch(new QQEvent("event-3", 0, 3L, "UNKNOWN", data, data));
            }
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("trace-content"));
        } finally {
            System.setOut(original);
        }
    }
}
