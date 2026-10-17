package top.hetaoawa.astra.storage;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JsonSupport {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private JsonSupport() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON encoding failed", e);
    }
  }

  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON decoding failed", e);
    }
  }

  @SuppressWarnings("unchecked")
  public static java.util.Map<String, Object> map(String json) {
    return read(json, java.util.Map.class);
  }
}
