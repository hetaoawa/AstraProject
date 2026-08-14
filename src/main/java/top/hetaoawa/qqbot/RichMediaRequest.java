package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** C2C/群聊富媒体上传请求体的构建器。 */
public final class RichMediaRequest {
    /** QQ 图片文件类型。 */
    public static final int IMAGE = 1;
    /** QQ 视频文件类型。 */
    public static final int VIDEO = 2;
    /** QQ 音频文件类型。 */
    public static final int AUDIO = 3;
    /** QQ 普通文件类型。 */
    public static final int FILE = 4;

    private final ObjectNode body;

    private RichMediaRequest(int fileType) {
        if (fileType < IMAGE || fileType > FILE) {
            throw new IllegalArgumentException("fileType must be 1 (image), 2 (video), 3 (audio), or 4 (file)");
        }
        body = JsonNodeFactory.instance.objectNode().put("file_type", fileType);
    }

    /** 创建来源为远程 URL 的媒体请求。 */
    public static RichMediaRequest fromUrl(int fileType, String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url must not be blank");
        return new RichMediaRequest(fileType).put("url", url);
    }

    /** 使用已准备的上传 ID 和文件名创建媒体请求。 */
    public static RichMediaRequest fromFileInfo(int fileType, String fileName, String uploadId) {
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName must not be blank");
        if (uploadId == null || uploadId.isBlank()) throw new IllegalArgumentException("uploadId must not be blank");
        return new RichMediaRequest(fileType).put("file_name", fileName).put("upload_id", uploadId);
    }

    /** 使用 Base64 数据创建媒体请求。 */
    public static RichMediaRequest fromBase64(int fileType, String base64Data) {
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("base64Data must not be blank");
        }
        return new RichMediaRequest(fileType).put("file_data", base64Data);
    }

    /** 设置上传后是否由 QQ 自动发送媒体消息。 */
    public RichMediaRequest serverSendsMessage(boolean enabled) {
        body.put("srv_send_msg", enabled);
        return this;
    }

    /** 添加或替换字符串字段。 */
    public RichMediaRequest put(String field, String value) {
        body.put(field, value);
        return this;
    }

    /** 添加或替换数字字段。 */
    public RichMediaRequest put(String field, long value) {
        body.put(field, value);
        return this;
    }

    /** 返回该请求的防御性 JSON 副本。 */
    public JsonNode toJson() {
        return body.deepCopy();
    }
}
