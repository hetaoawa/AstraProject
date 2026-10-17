package top.hetaoawa.astra.core;

import top.hetaoawa.astra.api.Event;

public interface ObservationStore {
  void observe(Event event);
}
