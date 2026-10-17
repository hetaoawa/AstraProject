package top.hetaoawa.astra.api;

import java.util.List;

public interface AstraPlugin {
  default void onEnable(PluginContext context) throws Exception {}

  default void onDisable(PluginContext context) throws Exception {}

  default void onError(HandlerFailure failure, PluginContext context) {}

  default List<Object> handlers() {
    return List.of(this);
  }
}
