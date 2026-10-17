package top.hetaoawa.astra.core;

import top.hetaoawa.astra.api.Event;

public interface EventCodec {
  String encode(Event event);

  Event decode(String json);
}
