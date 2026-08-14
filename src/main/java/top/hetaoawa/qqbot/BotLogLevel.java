package top.hetaoawa.qqbot;

/** AstraQQBot 框架控制台日志级别阈值。 */
public enum BotLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    boolean allows(BotLogLevel eventLevel) {
        return this != OFF && eventLevel.ordinal() >= ordinal();
    }
}
