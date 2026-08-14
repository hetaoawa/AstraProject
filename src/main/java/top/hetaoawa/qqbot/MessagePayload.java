package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Map;

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

    public static MessagePayload markdownTemplate(String templateId,
                                                  Map<String, ? extends Collection<String>> parameters) {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        ObjectNode node = MAPPER.createObjectNode().put("msg_type", 2);
        ObjectNode markdown = node.putObject("markdown").put("custom_template_id", templateId);
        var params = markdown.putArray("params");
        if (parameters != null) {
            parameters.forEach((key, values) -> {
                ObjectNode parameter = params.addObject().put("key", key);
                var valueArray = parameter.putArray("values");
                if (values != null) values.forEach(valueArray::add);
            });
        }
        return new MessagePayload(node);
    }

    public static MessagePayload media(String fileInfo) {
        if (fileInfo == null || fileInfo.isBlank()) {
            throw new IllegalArgumentException("fileInfo must not be blank");
        }
        ObjectNode node = MAPPER.createObjectNode().put("msg_type", 7);
        node.putObject("media").put("file_info", fileInfo);
        return new MessagePayload(node);
    }

    public static MessagePayload ark(int templateId, JsonNode keyValues) {
        if (templateId <= 0) throw new IllegalArgumentException("templateId must be positive");
        if (keyValues == null || !keyValues.isArray()) {
            throw new IllegalArgumentException("keyValues must be a JSON array");
        }
        ObjectNode node = MAPPER.createObjectNode().put("msg_type", 3);
        node.putObject("ark").put("template_id", templateId).set("kv", keyValues.deepCopy());
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

    public MessagePayload keyboardTemplate(String keyboardId) {
        if (keyboardId == null || keyboardId.isBlank()) {
            throw new IllegalArgumentException("keyboardId must not be blank");
        }
        body.putObject("keyboard").put("id", keyboardId);
        return this;
    }

    public MessagePayload keyboardContent(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw new IllegalArgumentException("content must be a JSON object");
        }
        body.putObject("keyboard").set("content", content.deepCopy());
        return this;
    }

    public MessagePayload messageSequence(int sequence) {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        body.put("msg_seq", sequence);
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
