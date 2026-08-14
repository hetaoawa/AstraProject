package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A progressive C2C message update. */
public final class StreamMessagePayload {
    public static final int GENERATING = 1;
    public static final int COMPLETED = 10;

    private final ObjectNode body = JsonNodeFactory.instance.objectNode();

    private StreamMessagePayload(String content, int index, int state) {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        body.put("input_mode", "replace");
        body.put("input_state", state);
        body.put("index", index);
        body.put("content_type", "markdown");
        body.put("content_raw", content == null ? "" : content);
    }

    public static StreamMessagePayload markdown(String content, int index, boolean completed) {
        return new StreamMessagePayload(content, index, completed ? COMPLETED : GENERATING);
    }

    public StreamMessagePayload replyTo(String messageId, int messageSequence) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId must not be blank");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        body.put("msg_id", messageId).put("msg_seq", messageSequence);
        return this;
    }

    public StreamMessagePayload streamMessageId(String streamMessageId) {
        if (streamMessageId == null || streamMessageId.isBlank()) {
            throw new IllegalArgumentException("streamMessageId must not be blank");
        }
        body.put("stream_msg_id", streamMessageId);
        return this;
    }

    public StreamMessagePayload put(String field, String value) {
        body.put(field, value);
        return this;
    }

    public JsonNode toJson() {
        return body.deepCopy();
    }
}
