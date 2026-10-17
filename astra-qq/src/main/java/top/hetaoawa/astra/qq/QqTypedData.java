package top.hetaoawa.astra.qq;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** Reads only the framework's explicit record classes, never classes named by packets. */
final class QqTypedData {
  private QqTypedData() {}

  static <T> T read(Class<T> type, Map<String, Object> raw) {
    try {
      var components = type.getRecordComponents();
      Object[] values = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        var c = components[i];
        types[i] = c.getType();
        String key = c.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        values[i] =
            c.getName().equals("raw")
                ? Collections.unmodifiableMap(new LinkedHashMap<>(raw))
                : value(c.getGenericType(), raw.get(key));
      }
      return type.getDeclaredConstructor(types).newInstance(values);
    } catch (ReflectiveOperationException e) {
      throw new IllegalArgumentException("Cannot read typed QQ data " + type.getSimpleName(), e);
    }
  }

  private static Object value(Type type, Object value) {
    if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
      if (!(value instanceof List<?> list)) return List.of();
      return list.stream().map(v -> value(p.getActualTypeArguments()[0], v)).toList();
    }
    if (value == null) return null;
    if (type == String.class) return QqJson.string(value);
    if (type == Integer.class || type == Long.class) {
      try {
        long n = Long.parseLong(QqJson.string(value));
        if (type == Integer.class) return Math.toIntExact(n);
        return n;
      } catch (RuntimeException e) {
        return null;
      }
    }
    if (type == Instant.class) {
      try {
        return OffsetDateTime.parse(QqJson.string(value)).toInstant();
      } catch (RuntimeException e) {
        try {
          return Instant.ofEpochSecond(Long.parseLong(QqJson.string(value)));
        } catch (RuntimeException ignored) {
          return null;
        }
      }
    }
    if (type instanceof Class<?> c && c.isRecord())
      return value instanceof Map<?, ?> ? read(c, QqJson.object(value)) : null;
    return value;
  }
}
