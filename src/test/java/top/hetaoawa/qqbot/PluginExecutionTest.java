package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginExecutionTest {
    @Test
    void dispatchReturnsWhilePluginRunsOnItsOwnThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> worker = new AtomicReference<>();
        try (QQBot bot = bot()) {
            bot.plugin("slow plugin").onMessage(message -> {
                worker.set(Thread.currentThread().getName());
                started.countDown();
                await(release);
            });

            String caller = Thread.currentThread().getName();
            bot.dispatch(messageEvent("event-1"));

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNotEquals(caller, worker.get());
            assertTrue(worker.get().startsWith("astraqqbot-plugin-0-slow_plugin-"));
            release.countDown();
        }
    }

    @Test
    void defaultPoolRunsTwoTasksConcurrentlyAndSeparatesPlugins() throws Exception {
        CountDownLatch firstPluginStarted = new CountDownLatch(2);
        CountDownLatch secondPluginStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> workers = ConcurrentHashMap.newKeySet();
        try (QQBot bot = bot()) {
            bot.plugin("first").onMessage(message -> {
                workers.add(Thread.currentThread().getName());
                firstPluginStarted.countDown();
                await(release);
            });
            bot.plugin("second").onMessage(message -> {
                workers.add(Thread.currentThread().getName());
                secondPluginStarted.countDown();
                await(release);
            });

            bot.dispatch(messageEvent("event-1"));
            bot.dispatch(messageEvent("event-2"));

            assertTrue(firstPluginStarted.await(2, TimeUnit.SECONDS));
            assertTrue(secondPluginStarted.await(2, TimeUnit.SECONDS));
            assertTrue(workers.stream().anyMatch(name -> name.contains("-first-")));
            assertTrue(workers.stream().anyMatch(name -> name.contains("-second-")));
            release.countDown();
        }
    }

    @Test
    void boundedQueueRejectsWithoutRunningOnDispatchThread() throws Exception {
        PluginExecutionOptions options = PluginExecutionOptions.builder()
                .threads(1).queueCapacity(1).build();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        try (QQBot bot = bot()) {
            bot.onError(error -> {
                if (error instanceof RejectedExecutionException) {
                    reported.set(error);
                    rejected.countDown();
                }
            });
            bot.plugin("bounded", options).onMessage(message -> {
                calls.incrementAndGet();
                firstStarted.countDown();
                await(release);
            });

            bot.dispatch(messageEvent("event-1"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            bot.dispatch(messageEvent("event-2"));
            bot.dispatch(messageEvent("event-3"));

            assertTrue(rejected.await(2, TimeUnit.SECONDS));
            assertTrue(reported.get().getMessage().contains("queue=1/1"));
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(waitUntil(() -> calls.get() == 2, Duration.ofSeconds(2)));
        }
    }

    @Test
    void reusesNamedRuntimeAndRejectsConflictingOptions() {
        try (QQBot bot = bot()) {
            QQBot.Plugin first = bot.plugin("shared");
            QQBot.Plugin second = bot.plugin("shared");
            assertEquals(first.executionOptions(), second.executionOptions());
            first.onMessage(message -> { });
            second.onMessage(message -> { });

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> bot.plugin("shared", PluginExecutionOptions.builder()
                            .threads(1).queueCapacity(1).build()));
            assertTrue(error.getMessage().contains("different execution options"));
        }
    }

    @Test
    void rejectsNewPluginAfterBotCloses() {
        QQBot bot = bot();
        bot.close();
        assertThrows(IllegalStateException.class, () -> bot.plugin("late"));
    }

    @Test
    void validatesAndCopiesPluginConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> PluginExecutionOptions.builder().threads(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> PluginExecutionOptions.builder().queueCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder()
                .appId("app").clientSecret("secret").pluginExecutorThreads(0).build());
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder()
                .appId("app").clientSecret("secret").pluginQueueCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () -> BotConfig.builder()
                .appId("app").clientSecret("secret").pluginShutdownTimeout(Duration.ZERO).build());

        BotConfig config = BotConfig.builder().appId("app").clientSecret("secret")
                .pluginExecutorThreads(3)
                .pluginQueueCapacity(17)
                .pluginShutdownTimeout(Duration.ofSeconds(7))
                .build();
        BotConfig copied = config.toBuilder().build();
        assertEquals(3, copied.pluginExecutorThreads());
        assertEquals(17, copied.pluginQueueCapacity());
        assertEquals(Duration.ofSeconds(7), copied.pluginShutdownTimeout());
    }

    @Test
    void closeInterruptsPluginAfterTimeoutAndStopsWorker() throws Exception {
        BotConfig config = BotConfig.builder().appId("app").clientSecret("secret")
                .pluginShutdownTimeout(Duration.ofMillis(100))
                .logLevel(BotLogLevel.OFF)
                .build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        QQBot bot = QQBot.create(config);
        bot.plugin("shutdown").onMessage(message -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        bot.dispatch(messageEvent("event-1"));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        bot.close();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> thread.isAlive()
                                && thread.getName().startsWith("astraqqbot-plugin-0-shutdown-")),
                Duration.ofSeconds(2)));
    }

    @Test
    void closeWaitsForPluginThatFinishesWithinTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        QQBot bot = bot();
        bot.plugin("graceful").onMessage(message -> {
            started.countDown();
            await(release);
        });
        bot.dispatch(messageEvent("event-1"));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        Thread closer = new Thread(bot::close, "plugin-close-test");
        closer.start();
        assertTrue(waitUntil(closer::isAlive, Duration.ofSeconds(1)));
        release.countDown();
        closer.join(2_000);

        assertTrue(!closer.isAlive());
        assertTrue(waitUntil(() -> Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(thread -> thread.isAlive()
                                && thread.getName().startsWith("astraqqbot-plugin-0-graceful-")),
                Duration.ofSeconds(2)));
    }

    @Test
    void listenerFailureDoesNotPreventAnotherPlugin() throws Exception {
        CountDownLatch otherRan = new CountDownLatch(1);
        try (QQBot bot = bot()) {
            bot.plugin("failing").onMessage(message -> {
                throw new IllegalStateException("boom");
            });
            bot.plugin("healthy").onMessage(message -> otherRan.countDown());

            bot.dispatch(messageEvent("event-1"));

            assertTrue(otherRan.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void commandsRemainOrderedWithoutBlockingDispatch() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();
        try (QQBot bot = bot()) {
            bot.plugin("high").onCommand("hello", 100, true, command -> {
                calls.add("high");
                firstStarted.countDown();
                await(release);
            });
            bot.plugin("low").onCommand("hello", 10, false, command -> {
                calls.add("low");
                finished.countDown();
            });
            bot.plugin("blocked").onCommand("hello", 0, true, command -> calls.add("blocked"));

            bot.dispatch(messageEvent("event-1"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("high"), calls);
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("high", "low"), calls);
        }
    }

    private static QQBot bot() {
        return QQBot.create(BotConfig.builder().appId("app").clientSecret("secret")
                .logLevel(BotLogLevel.OFF).build());
    }

    private static QQEvent messageEvent(String id) {
        var data = JsonNodeFactory.instance.objectNode()
                .put("id", "message-" + id)
                .put("content", "/hello");
        data.putObject("author").put("user_openid", "user-1");
        return new QQEvent(id, 0, 1L, "C2C_MESSAGE_CREATE", data, data);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
