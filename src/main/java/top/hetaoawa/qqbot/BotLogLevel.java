package top.hetaoawa.qqbot;

/** Console logging threshold used by AstraQQBot. */
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
