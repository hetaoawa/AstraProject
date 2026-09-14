package top.hetaoawa.qqbot;

/** A synchronous plugin callback that may propagate checked exceptions. */
@FunctionalInterface
public interface EventHandler<T> {
    void handle(T event) throws Exception;
}
