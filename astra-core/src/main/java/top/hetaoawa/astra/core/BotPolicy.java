package top.hetaoawa.astra.core;

import top.hetaoawa.astra.api.BotId;

public record BotPolicy(
    BotId bot, int concurrency, int queueCapacity, String unionScope, boolean unionEnabled) {}
