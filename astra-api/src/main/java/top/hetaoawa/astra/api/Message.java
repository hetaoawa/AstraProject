package top.hetaoawa.astra.api;

import java.util.List;
import java.util.Map;

public record Message(
    String id,
    String index,
    String text,
    List<Segment> segments,
    boolean toMe,
    Map<String, Object> raw,
    MessageContent content) {
  public Message(
      String id,
      String index,
      String text,
      List<Segment> segments,
      boolean toMe,
      Map<String, Object> raw) {
    this(id, index, text, segments, toMe, raw, null);
  }

  public String displayText() {
    if (content != null && content.reference() != null) return content.reference().displayText();
    if (content != null && content.merged() != null) return content.merged().displayText();
    return text;
  }

  public Message {
    index = index == null ? "" : index;
    text = text == null ? "" : text;
    segments = segments == null ? List.of() : List.copyOf(segments);
    raw =
        raw == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
  }

  public record Segment(String type, Map<String, Object> data) {
    public Segment {
      data = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }
  }
}
