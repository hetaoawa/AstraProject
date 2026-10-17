package top.hetaoawa.astra.core;

import top.hetaoawa.astra.api.*;

@FunctionalInterface
public interface ContextFactory {
  PluginContext create(String pluginId, BotId bot);
}
