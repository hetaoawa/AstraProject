package top.hetaoawa.qqbot;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 单个插件的不可变并发、排队和超时配置。
 *
 * <p>未设置 {@link #taskTimeout()} 时继承 {@link BotConfig#pluginTaskTimeout()}；并发数和队列长度
 * 使用本对象中配置的并发数和队列长度。调用 {@link Builder#disableTaskTimeout()} 可关闭任务超时。</p>
 */
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

    /** 返回并发数默认为 256、队列长度默认为 512 的 Builder。 */
    public static Builder builder() { return new Builder(); }

    static PluginExecutionOptions defaults(BotConfig config) {
        return builder().maxConcurrentTasks(config.maxConcurrentTasks())
                .maxPendingTasks(config.maxPendingTasks()).build();
    }

    /** 返回插件允许同时运行的最大回调数。 */
    public int maxConcurrentTasks() { return maxConcurrentTasks; }
    /** 返回插件允许等待的最大回调数，可以为 0。 */
    public int maxPendingTasks() { return maxPendingTasks; }
    /** 返回显式任务超时；为空表示继承 Bot 默认超时。 */
    public Optional<Duration> taskTimeout() { return taskTimeout; }
    /** 返回是否显式禁用了任务超时。 */
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

    /** {@link PluginExecutionOptions} 的可变 Builder。 */
    public static final class Builder {
        private int maxConcurrentTasks = 256;
        private int maxPendingTasks = 512;
        private Duration taskTimeout;
        private boolean taskTimeoutDisabled;

        /** 设置插件允许同时运行的最大回调数。 */
        public Builder maxConcurrentTasks(int value) { this.maxConcurrentTasks = value; return this; }
        /** 设置最大待处理任务数，可以为 0。 */
        public Builder maxPendingTasks(int value) { this.maxPendingTasks = value; return this; }
        /** 设置进入 {@code RUNNING} 后的任务超时；排队时间不计入。 */
        public Builder taskTimeout(Duration value) {
            this.taskTimeout = Objects.requireNonNull(value, "taskTimeout");
            this.taskTimeoutDisabled = false;
            return this;
        }
        /** 显式禁用该插件的任务超时。 */
        public Builder disableTaskTimeout() {
            this.taskTimeout = null;
            this.taskTimeoutDisabled = true;
            return this;
        }
        /** 校验并创建不可变配置。 */
        public PluginExecutionOptions build() { return new PluginExecutionOptions(this); }
    }
}
