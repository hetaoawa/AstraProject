package io.github.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Extensible message request. Unknown official fields can be added through {@link #put}. */
public final class MessagePayload {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectNode body;

    private MessagePayload(ObjectNode body) {
        this.body = body;
    }

    public static MessagePayload text(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("msg_type", 0);
        node.put("content", content == null ? "" : content);
        return new MessagePayload(node);
    }

    public static MessagePayload markdown(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("msg_type", 2);
        node.putObject("markdown").put("content", content == null ? "" : content);
        return new MessagePayload(node);
    }

    public static MessagePayload raw(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("message body must be a JSON object");
        }
        return new MessagePayload((ObjectNode) body.deepCopy());
    }

    public MessagePayload put(String field, String value) {
        body.put(field, value);
        return this;
    }

    public MessagePayload put(String field, long value) {
        body.put(field, value);
        return this;
    }

    public MessagePayload put(String field, boolean value) {
        body.put(field, value);
        return this;
    }

    public MessagePayload set(String field, JsonNode value) {
        body.set(field, value);
        return this;
    }

    public MessagePayload replyTo(QQMessageEvent event) {
        if (event == null || event.messageId() == null || event.messageId().isBlank()) {
            throw new IllegalArgumentException("event must contain a message id");
        }
        body.put("msg_id", event.messageId());
        body.put("msg_seq", 1);
        return this;
    }

    public MessagePayload eventReplyTo(QQEvent event) {
        if (event == null || event.id() == null || event.id().isBlank()) {
            throw new IllegalArgumentException("event must contain an event id");
        }
        body.put("event_id", event.id());
        return this;
    }

    ObjectNode copyNode() {
        return body.deepCopy();
    }

    public JsonNode toJson() {
        return body.deepCopy();
    }
}
