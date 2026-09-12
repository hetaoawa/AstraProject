package top.hetaoawa.qqbot;

/** 单个插件执行器的不可变配置。 */
public final class PluginExecutionOptions {
    private final int threads;
    private final int queueCapacity;

    private PluginExecutionOptions(Builder builder) {
        this.threads = positive(builder.threads, "threads");
        this.queueCapacity = positive(builder.queueCapacity, "queueCapacity");
    }

    /** 创建使用框架默认值的构建器。 */
    public static Builder builder() {
        return new Builder();
    }

    static PluginExecutionOptions of(int threads, int queueCapacity) {
        return builder().threads(threads).queueCapacity(queueCapacity).build();
    }

    /** 返回插件工作线程数。 */
    public int threads() {
        return threads;
    }

    /** 返回插件等待队列容量。 */
    public int queueCapacity() {
        return queueCapacity;
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PluginExecutionOptions options
                && threads == options.threads
                && queueCapacity == options.queueCapacity;
    }

    @Override
    public int hashCode() {
        return 31 * threads + queueCapacity;
    }

    @Override
    public String toString() {
        return "PluginExecutionOptions[threads=" + threads + ", queueCapacity=" + queueCapacity + "]";
    }

    /** {@link PluginExecutionOptions} 的构建器。 */
    public static final class Builder {
        private int threads = 2;
        private int queueCapacity = 256;

        /** 设置插件工作线程数。 */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /** 设置插件有界等待队列容量。 */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /** 校验并创建不可变配置。 */
        public PluginExecutionOptions build() {
            return new PluginExecutionOptions(this);
        }
    }
}
