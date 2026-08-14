package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Map;

/** QQ 消息请求体的可扩展构建器，官方新增字段可通过 {@link #put} 和 {@link #set} 添加。 */
public final class MessagePayload {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectNode body;

    private MessagePayload(ObjectNode body) {
        this.body = body;
    }

    /** 创建纯文本消息载荷。 */
    public static MessagePayload text(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("msg_type", 0);
        node.put("content", content == null ? "" : content);
        return new MessagePayload(node);
    }

    /** 创建 Markdown 消息载荷。 */
    public static MessagePayload markdown(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("msg_type", 2);
        node.putObject("markdown").put("content", content == null ? "" : content);
        return new MessagePayload(node);
    }

    /** 使用模板 ID 和参数创建 Markdown 自定义模板载荷。 */
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

    /** 使用已准备的 file_info 创建富媒体载荷。 */
    public static MessagePayload media(String fileInfo) {
        if (fileInfo == null || fileInfo.isBlank()) {
            throw new IllegalArgumentException("fileInfo must not be blank");
        }
        ObjectNode node = MAPPER.createObjectNode().put("msg_type", 7);
        node.putObject("media").put("file_info", fileInfo);
        return new MessagePayload(node);
    }

    /** 创建 Ark 模板载荷。 */
    public static MessagePayload ark(int templateId, JsonNode keyValues) {
        if (templateId <= 0) throw new IllegalArgumentException("templateId must be positive");
        if (keyValues == null || !keyValues.isArray()) {
            throw new IllegalArgumentException("keyValues must be a JSON array");
        }
        ObjectNode node = MAPPER.createObjectNode().put("msg_type", 3);
        node.putObject("ark").put("template_id", templateId).set("kv", keyValues.deepCopy());
        return new MessagePayload(node);
    }

    /** 从已有 JSON 对象创建消息载荷。 */
    public static MessagePayload raw(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("message body must be a JSON object");
        }
        return new MessagePayload((ObjectNode) body.deepCopy());
    }

    /** 添加或替换字符串字段。 */
    public MessagePayload put(String field, String value) {
        body.put(field, value);
        return this;
    }

    /** 添加或替换数字字段。 */
    public MessagePayload put(String field, long value) {
        body.put(field, value);
        return this;
    }

    /** 添加或替换布尔字段。 */
    public MessagePayload put(String field, boolean value) {
        body.put(field, value);
        return this;
    }

    /** 添加或替换 JSON 字段。 */
    public MessagePayload set(String field, JsonNode value) {
        body.set(field, value);
        return this;
    }

    /** 添加键盘模板引用。 */
    public MessagePayload keyboardTemplate(String keyboardId) {
        if (keyboardId == null || keyboardId.isBlank()) {
            throw new IllegalArgumentException("keyboardId must not be blank");
        }
        body.putObject("keyboard").put("id", keyboardId);
        return this;
    }

    /** 添加内联键盘内容。 */
    public MessagePayload keyboardContent(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw new IllegalArgumentException("content must be a JSON object");
        }
        body.putObject("keyboard").set("content", content.deepCopy());
        return this;
    }

    /** 设置组合回复使用的消息序号。 */
    public MessagePayload messageSequence(int sequence) {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        body.put("msg_seq", sequence);
        return this;
    }

    /** 根据标准化消息事件添加回复字段。 */
    public MessagePayload replyTo(QQMessageEvent event) {
        if (event == null || event.messageId() == null || event.messageId().isBlank()) {
            throw new IllegalArgumentException("event must contain a message id");
        }
        body.put("msg_id", event.messageId());
        body.put("msg_seq", 1);
        return this;
    }

    /** 根据原始事件封装添加回复字段。 */
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

    /** 返回该载荷的防御性 JSON 副本。 */
    public JsonNode toJson() {
        return body.deepCopy();
    }
}
