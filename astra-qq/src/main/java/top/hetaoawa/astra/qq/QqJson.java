package top.hetaoawa.astra.qq;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.*;

public final class QqJson {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode protocol JSON", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(String json) {
    try {
      return MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid protocol JSON", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  public static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static java.util.List<?> array(Object value) {
    return value instanceof java.util.List<?> list ? list : java.util.List.of();
  }

  public static long number(Object value, long fallback) {
    try {
      return Long.parseLong(string(value));
    } catch (Exception e) {
      return fallback;
    }
  }
}
