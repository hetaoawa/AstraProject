package top.hetaoawa.astra.core;

import java.util.List;
import top.hetaoawa.astra.api.Event;

public interface ProcessingStore {
  boolean blocked(String eventKey);

  record Plan(String pluginId, String handlerId) {}

  boolean completed(String eventKey);

  List<Plan> prepare(Event event, List<Plan> handlers);

  boolean start(String eventKey, String handlerId);

  void finishHandler(String eventKey, String handlerId, String state, String error);

  void finishEvent(Event event, String state);

  void recoverInterrupted();
}
