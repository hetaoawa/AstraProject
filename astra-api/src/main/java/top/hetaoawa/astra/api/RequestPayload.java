package top.hetaoawa.astra.api;

import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/** Typed protocol records encode their fields using platform snake_case names. */
public interface RequestPayload {
  default Map<String, Object> toMap() {
    if (!getClass().isRecord())
      throw new IllegalStateException("Protocol payload must be a record");
    var result = new LinkedHashMap<String, Object>();
    for (RecordComponent component : getClass().getRecordComponents())
      try {
        Object value = component.getAccessor().invoke(this);
        if (value != null)
          result.put(
              component.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT),
              wire(value));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    return Collections.unmodifiableMap(result);
  }

  private static Object wire(Object value) {
    if (value instanceof WireValue w) return w.wireValue();
    if (value instanceof RequestPayload request) return request.toMap();
    if (value instanceof TemporalAccessor) return value.toString();
    if (value instanceof Collection<?> c) return c.stream().map(RequestPayload::wire).toList();
    if (value instanceof Map<?, ?> m) {
      var result = new LinkedHashMap<String, Object>();
      m.forEach(
          (k, v) -> {
            if (v != null) result.put(k.toString(), wire(v));
          });
      return result;
    }
    return value;
  }

  interface WireValue {
    Object wireValue();
  }
}
