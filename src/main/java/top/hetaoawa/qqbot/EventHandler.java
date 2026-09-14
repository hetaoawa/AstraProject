package top.hetaoawa.qqbot;

/** 可抛出受检异常的同步插件回调。 */
@FunctionalInterface
public interface EventHandler<T> {
    void handle(T event) throws Exception;
}
