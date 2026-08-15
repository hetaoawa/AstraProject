package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** C2C 流式消息更新请求体的构建器。 */
public final class StreamMessagePayload {
    /** 表示流式内容仍在生成中的状态码。 */
    public static final int GENERATING = 1;
    /** 表示流式内容已生成完成的状态码。 */
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

    /** 创建 Markdown 流式消息分片。 */
    public static StreamMessagePayload markdown(String content, int index, boolean completed) {
        return new StreamMessagePayload(content, index, completed ? COMPLETED : GENERATING);
    }

    /** 设置回复流对应的消息 ID 和序号。 */
    public StreamMessagePayload replyTo(String messageId, int messageSequence) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId must not be blank");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        body.put("msg_id", messageId).put("msg_seq", messageSequence);
        return this;
    }

    /** 设置要更新的已有流式消息 ID。 */
    public StreamMessagePayload streamMessageId(String streamMessageId) {
        if (streamMessageId == null || streamMessageId.isBlank()) {
            throw new IllegalArgumentException("streamMessageId must not be blank");
        }
        body.put("stream_msg_id", streamMessageId);
        return this;
    }

    /** 添加或替换字符串字段。 */
    public StreamMessagePayload put(String field, String value) {
        body.put(field, value);
        return this;
    }

    /** 返回该载荷的防御性 JSON 副本。 */
    public JsonNode toJson() {
        return body.deepCopy();
    }
}
