package top.hetaoawa.qqbot;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable concurrency, backpressure and timeout overrides for one plugin. */
public final class PluginExecutionOptions {
    private final int maxConcurrentTasks;
    private final int maxPendingTasks;
    private final Optional<Duration> taskTimeout;
    private final boolean taskTimeoutDisabled;

    private PluginExecutionOptions(Builder builder) {
        this.maxConcurrentTasks = positive(builder.maxConcurrentTasks, "maxConcurrentTasks");
        this.maxPendingTasks = nonNegative(builder.maxPendingTasks, "maxPendingTasks");
        this.taskTimeout = Optional.ofNullable(builder.taskTimeout);
        this.taskTimeoutDisabled = builder.taskTimeoutDisabled;
        taskTimeout.ifPresent(timeout -> positive(timeout, "taskTimeout"));
        if (taskTimeoutDisabled && taskTimeout.isPresent()) {
            throw new IllegalStateException("task timeout cannot be both configured and disabled");
        }
    }

    public static Builder builder() { return new Builder(); }

    static PluginExecutionOptions defaults(BotConfig config) {
        return builder().maxConcurrentTasks(config.maxConcurrentTasks())
                .maxPendingTasks(config.maxPendingTasks()).build();
    }

    public int maxConcurrentTasks() { return maxConcurrentTasks; }
    public int maxPendingTasks() { return maxPendingTasks; }
    /** Empty means inherit the bot default. */
    public Optional<Duration> taskTimeout() { return taskTimeout; }
    public boolean isTaskTimeoutDisabled() { return taskTimeoutDisabled; }

    Duration effectiveTaskTimeout(Duration globalDefault) {
        return taskTimeoutDisabled ? null : taskTimeout.orElse(globalDefault);
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    @Override public boolean equals(Object other) {
        return other instanceof PluginExecutionOptions options
                && maxConcurrentTasks == options.maxConcurrentTasks
                && maxPendingTasks == options.maxPendingTasks
                && taskTimeout.equals(options.taskTimeout)
                && taskTimeoutDisabled == options.taskTimeoutDisabled;
    }
    @Override public int hashCode() {
        return Objects.hash(maxConcurrentTasks, maxPendingTasks, taskTimeout, taskTimeoutDisabled);
    }
    @Override public String toString() {
        return "PluginExecutionOptions[maxConcurrentTasks=" + maxConcurrentTasks
                + ", maxPendingTasks=" + maxPendingTasks + ", taskTimeout="
                + (taskTimeoutDisabled ? "disabled" : taskTimeout.map(Object::toString).orElse("inherited")) + "]";
    }

    public static final class Builder {
        private int maxConcurrentTasks = 256;
        private int maxPendingTasks = 512;
        private Duration taskTimeout;
        private boolean taskTimeoutDisabled;

        public Builder maxConcurrentTasks(int value) { this.maxConcurrentTasks = value; return this; }
        public Builder maxPendingTasks(int value) { this.maxPendingTasks = value; return this; }
        public Builder taskTimeout(Duration value) {
            this.taskTimeout = Objects.requireNonNull(value, "taskTimeout");
            this.taskTimeoutDisabled = false;
            return this;
        }
        public Builder disableTaskTimeout() {
            this.taskTimeout = null;
            this.taskTimeoutDisabled = true;
            return this;
        }
        public PluginExecutionOptions build() { return new PluginExecutionOptions(this); }
    }
}
