package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A progressive C2C message update. */
/** Fluent builder for streaming message request bodies. */
public final class StreamMessagePayload {
    /** Stream chunk status indicating generation is still in progress. */
    public static final int GENERATING = 1;
    /** Stream chunk status indicating generation is complete. */
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

    /** Creates a Markdown stream chunk. */
    public static StreamMessagePayload markdown(String content, int index, boolean completed) {
        return new StreamMessagePayload(content, index, completed ? COMPLETED : GENERATING);
    }

    /** Sets the message ID and sequence for a reply stream. */
    public StreamMessagePayload replyTo(String messageId, int messageSequence) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId must not be blank");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        body.put("msg_id", messageId).put("msg_seq", messageSequence);
        return this;
    }

    /** Sets an existing stream message ID for an update. */
    public StreamMessagePayload streamMessageId(String streamMessageId) {
        if (streamMessageId == null || streamMessageId.isBlank()) {
            throw new IllegalArgumentException("streamMessageId must not be blank");
        }
        body.put("stream_msg_id", streamMessageId);
        return this;
    }

    /** Adds or replaces a string field. */
    public StreamMessagePayload put(String field, String value) {
        body.put(field, value);
        return this;
    }

    /** Returns a defensive JSON copy of this payload. */
    public JsonNode toJson() {
        return body.deepCopy();
    }
}
