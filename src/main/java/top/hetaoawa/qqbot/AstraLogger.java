package top.hetaoawa.qqbot;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Per-bot console logger with no external logging dependency. */
final class AstraLogger {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final BotLogLevel threshold;
    private final boolean eventPayloads;
    private final PrintStream output;
    private final Clock clock;

    AstraLogger(BotConfig config) {
        this(config.logLevel(), config.logEventPayloads(), System.out, Clock.systemDefaultZone());
    }

    AstraLogger(BotLogLevel threshold, boolean eventPayloads, PrintStream output, Clock clock) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        this.eventPayloads = eventPayloads;
        this.output = Objects.requireNonNull(output, "output");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean isEnabled(BotLogLevel level) {
        return threshold.allows(level);
    }

    boolean eventPayloadsEnabled() {
        return eventPayloads && isEnabled(BotLogLevel.TRACE);
    }

    void trace(String component, String message) { log(BotLogLevel.TRACE, component, message, null); }
    void debug(String component, String message) { log(BotLogLevel.DEBUG, component, message, null); }
    void info(String component, String message) { log(BotLogLevel.INFO, component, message, null); }
    void warn(String component, String message) { log(BotLogLevel.WARN, component, message, null); }
    void error(String component, String message, Throwable error) { log(BotLogLevel.ERROR, component, message, error); }

    private void log(BotLogLevel level, String component, String message, Throwable error) {
        if (!isEnabled(level)) return;
        Instant now = clock.instant();
        String line = "%s [%s] [%s] [%s] %s".formatted(
                TIMESTAMP.format(now), level, safe(component), Thread.currentThread().getName(), safe(message));
        synchronized (output) {
            output.println(line);
            if (error != null) error.printStackTrace(output);
        }
    }

    private static String safe(String value) {
        return value == null ? "-" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
