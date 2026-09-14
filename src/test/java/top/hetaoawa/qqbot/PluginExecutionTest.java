package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PluginExecutionTest {
    @Test
    void dispatchIsNonBlockingAndCallbacksUseNamedVirtualThreads() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        var threadName = new java.util.concurrent.atomic.AtomicReference<String>();
        try (QQBot bot = bot()) {
            bot.plugin("slow plugin").onMessage(message -> {
                virtual.set(Thread.currentThread().isVirtual());
                threadName.set(Thread.currentThread().getName());
                started.countDown();
                release.await();
            });
            long before = System.nanoTime();
            bot.dispatch(messageEvent("event-1"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(elapsedMs < 250, "dispatch blocked for " + elapsedMs + "ms");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(virtual.get());
            assertTrue(threadName.get().startsWith("astraqqbot-plugin-0-slow_plugin-"));
            release.countDown();
        }
    }

    @Test
    void onePluginRunsMoreThanTwoBlockingTasksAndPluginsAreIsolated() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(4);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> names = ConcurrentHashMap.newKeySet();
        try (QQBot bot = bot()) {
            bot.plugin("first").onMessage(event -> {
                names.add(Thread.currentThread().getName());
                firstStarted.countDown();
                release.await();
            });
            bot.plugin("second").onMessage(event -> {
                names.add(Thread.currentThread().getName());
                secondStarted.countDown();
            });
            for (int i = 0; i < 4; i++) bot.dispatch(messageEvent("event-" + i));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertTrue(names.stream().anyMatch(name -> name.contains("-first-")));
            assertTrue(names.stream().anyMatch(name -> name.contains("-second-")));
            release.countDown();
        }
    }

    @Test
    void timeoutStartsAtRunningAndInterruptsBlockingCallbackExactlyOnce() throws Exception {
        PluginExecutionOptions options = PluginExecutionOptions.builder()
                .maxConcurrentTasks(1).maxPendingTasks(2).taskTimeout(Duration.ofMillis(80)).build();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicInteger timeouts = new AtomicInteger();
        try (QQBot bot = bot()) {
            bot.onError(error -> {
                if (error instanceof TimeoutException) timeouts.incrementAndGet();
            });
            bot.plugin("timeout", options).onMessage(event -> {
                if (event.eventId().endsWith("1")) firstStarted.countDown(); else secondStarted.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { interrupted.countDown(); throw error; }
            });
            bot.dispatch(messageEvent("event-1"));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            bot.dispatch(messageEvent("event-2"));
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS), "queued task did not start after timeout");
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(waitUntil(() -> timeouts.get() == 2, Duration.ofSeconds(2)));
            assertEquals(2, timeouts.get());
        }
    }

    @Test
    void pluginCanOverrideAndDisableTaskTimeout() throws Exception {
        BotConfig config = BotConfig.builder().appId("app").clientSecret("secret")
                .pluginTaskTimeout(Duration.ofMillis(40)).logLevel(BotLogLevel.OFF).build();
        CountDownLatch adjusted = new CountDownLatch(1);
        CountDownLatch disabled = new CountDownLatch(1);
        AtomicInteger timeoutCount = new AtomicInteger();
        try (QQBot bot = QQBot.create(config)) {
            bot.onError(error -> { if (error instanceof TimeoutException) timeoutCount.incrementAndGet(); });
            bot.plugin("adjusted", PluginExecutionOptions.builder()
                    .taskTimeout(Duration.ofMillis(300)).build())
                    .onMessage(event -> { Thread.sleep(90); adjusted.countDown(); });
            bot.plugin("disabled", PluginExecutionOptions.builder().disableTaskTimeout().build())
                    .onMessage(event -> { Thread.sleep(90); disabled.countDown(); });
            bot.dispatch(messageEvent("event-1"));
            assertTrue(adjusted.await(1, TimeUnit.SECONDS));
            assertTrue(disabled.await(1, TimeUnit.SECONDS));
            assertEquals(0, timeoutCount.get());
        }
    }

    @Test
    void boundedQueueRejectsWithoutCallerExecution() throws Exception {
        PluginExecutionOptions options = PluginExecutionOptions.builder()
                .maxConcurrentTasks(1).maxPendingTasks(1).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (QQBot bot = bot()) {
            bot.onError(error -> { if (error instanceof RejectedExecutionException) rejected.countDown(); });
            bot.plugin("bounded", options).onMessage(event -> {
                calls.incrementAndGet(); started.countDown(); release.await();
            });
            bot.dispatch(messageEvent("event-1"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            bot.dispatch(messageEvent("event-2"));
            bot.dispatch(messageEvent("event-3"));
            assertTrue(rejected.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(waitUntil(() -> calls.get() == 2, Duration.ofSeconds(1)));
        }
    }

    @Test
    void pluginCloseCancelsPendingInterruptsRunningAndAllowsFreshRuntime() throws Exception {
        PluginExecutionOptions options = PluginExecutionOptions.builder()
                .maxConcurrentTasks(1).maxPendingTasks(2).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        QQBot bot = QQBot.create(BotConfig.builder().appId("app").clientSecret("secret")
                .pluginShutdownTimeout(Duration.ofMillis(80)).logLevel(BotLogLevel.OFF).build());
        try (bot) {
            QQBot.Plugin plugin = bot.plugin("closable", options);
            plugin.onMessage(event -> {
                calls.incrementAndGet(); started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException error) { interrupted.countDown(); throw error; }
            });
            bot.dispatch(messageEvent("event-1"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            bot.dispatch(messageEvent("event-2"));
            plugin.close();
            plugin.close();
            assertTrue(plugin.isClosing());
            assertTrue(plugin.isClosed());
            assertThrows(IllegalStateException.class, () -> plugin.onMessage(event -> { }));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            QQBot.Plugin replacement = bot.plugin("closable", options);
            assertFalse(replacement.isClosing());
            replacement.close();
        }
    }

    @Test
    void checkedExceptionReachesErrorChannelWithoutAffectingAnotherPlugin() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch healthy = new CountDownLatch(1);
        try (QQBot bot = bot()) {
            bot.onError(error -> { if (error instanceof java.io.IOException) reported.countDown(); });
            bot.plugin("broken").onMessage(event -> { throw new java.io.IOException("checked"); });
            bot.plugin("healthy").onMessage(event -> healthy.countDown());
            bot.dispatch(messageEvent("event-1"));
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertTrue(healthy.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void commandsStayOrderedAndTimeoutStopsTheChain() throws Exception {
        CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch timeout = new CountDownLatch(1);
        try (QQBot bot = bot()) {
            bot.onError(error -> { if (error instanceof TimeoutException) timeout.countDown(); });
            bot.plugin("high", PluginExecutionOptions.builder().taskTimeout(Duration.ofMillis(60)).build())
                    .onCommand("hello", 100, true, command -> {
                        calls.add("high"); new CountDownLatch(1).await();
                    });
            bot.plugin("low").onCommand("hello", 10, false, command -> calls.add("low"));
            bot.dispatch(messageEvent("event-1"));
            assertTrue(timeout.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(List.of("high"), calls);
        }
    }

    @Test
    void configurationDefaultsValidationAndCopyAreExplicit() {
        assertTrue(Runtime.version().feature() >= 21);
        assertTrue(assertDoesNotThrow(() -> java.nio.file.Files.readString(java.nio.file.Path.of("pom.xml")))
                .contains("<maven.compiler.release>21</maven.compiler.release>"));
        BotConfig defaults = BotConfig.builder().appId("app").clientSecret("secret").build();
        assertEquals(Duration.ofSeconds(60), defaults.pluginTaskTimeout());
        assertEquals(Duration.ofSeconds(30), defaults.pluginShutdownTimeout());
        assertEquals(256, defaults.maxConcurrentTasks());
        assertEquals(512, defaults.maxPendingTasks());
        assertThrows(IllegalArgumentException.class,
                () -> PluginExecutionOptions.builder().maxConcurrentTasks(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PluginExecutionOptions.builder().maxPendingTasks(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> PluginExecutionOptions.builder().taskTimeout(Duration.ZERO).build());
        BotConfig copied = defaults.toBuilder().build();
        assertEquals(defaults.pluginTaskTimeout(), copied.pluginTaskTimeout());
        assertEquals(defaults.maxConcurrentTasks(), copied.maxConcurrentTasks());
    }

    @Test
    void publicRegistrationApiHasNoAsyncMethodsOrFutureCallbacks() {
        for (Class<?> type : List.of(QQBot.class, QQBot.Plugin.class, QQBotCluster.class)) {
            assertTrue(Arrays.stream(type.getMethods()).map(Method::getName)
                    .noneMatch(name -> name.startsWith("on") && name.endsWith("Async")));
            Arrays.stream(type.getMethods()).filter(method -> method.getName().startsWith("on"))
                    .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                    .forEach(parameter -> assertNotEquals(java.util.function.Function.class, parameter));
        }
        Arrays.stream(QQOpenApi.class.getMethods())
                .forEach(method -> assertFalse(java.util.concurrent.CompletionStage.class
                        .isAssignableFrom(method.getReturnType()), method.toString()));
        assertEquals(MessageResponse.class, assertDoesNotThrow(() ->
                QQMessageEvent.class.getMethod("replyText", String.class)).getReturnType());
    }

    private static QQBot bot() {
        return QQBot.create(BotConfig.builder().appId("app").clientSecret("secret")
                .logLevel(BotLogLevel.OFF).build());
    }

    private static QQEvent messageEvent(String id) {
        var data = JsonNodeFactory.instance.objectNode().put("id", "message-" + id).put("content", "/hello");
        data.putObject("author").put("user_openid", "user-1");
        return new QQEvent(id, 0, 1L, "C2C_MESSAGE_CREATE", data, data);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
