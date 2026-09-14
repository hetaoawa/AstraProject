/**
 * Java 21 QQ Bot framework with managed synchronous plugin callbacks.
 *
 * <p>Every plugin callback is dispatched away from Gateway/Webhook receive threads and runs on a
 * framework-owned virtual thread. The framework applies per-plugin bounded concurrency and queueing,
 * a 60-second default running-task timeout, exception reporting, cooperative cancellation and
 * idempotent shutdown. Callback code may block normally and may throw checked exceptions.</p>
 *
 * <p>High-level message and OpenAPI operations return their result synchronously. Lifecycle methods
 * such as WebSocket startup may still expose futures. Interruption is cooperative: code that ignores
 * interruption cannot be safely force-stopped, and this framework never uses {@link Thread#stop()}.</p>
 */
package top.hetaoawa.qqbot;
