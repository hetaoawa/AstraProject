package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Request for the C2C/group rich-media upload endpoints. */
/** Fluent builder for rich-media upload request bodies. */
public final class RichMediaRequest {
    /** QQ image file type. */
    public static final int IMAGE = 1;
    /** QQ video file type. */
    public static final int VIDEO = 2;
    /** QQ audio file type. */
    public static final int AUDIO = 3;
    /** QQ generic file type. */
    public static final int FILE = 4;

    private final ObjectNode body;

    private RichMediaRequest(int fileType) {
        if (fileType < IMAGE || fileType > FILE) {
            throw new IllegalArgumentException("fileType must be 1 (image), 2 (video), 3 (audio), or 4 (file)");
        }
        body = JsonNodeFactory.instance.objectNode().put("file_type", fileType);
    }

    /** Creates a media request whose source is a remote URL. */
    public static RichMediaRequest fromUrl(int fileType, String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url must not be blank");
        return new RichMediaRequest(fileType).put("url", url);
    }

    /** Creates a media request from a prepared upload ID and file name. */
    public static RichMediaRequest fromFileInfo(int fileType, String fileName, String uploadId) {
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName must not be blank");
        if (uploadId == null || uploadId.isBlank()) throw new IllegalArgumentException("uploadId must not be blank");
        return new RichMediaRequest(fileType).put("file_name", fileName).put("upload_id", uploadId);
    }

    /** Creates a media request from Base64-encoded data. */
    public static RichMediaRequest fromBase64(int fileType, String base64Data) {
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("base64Data must not be blank");
        }
        return new RichMediaRequest(fileType).put("file_data", base64Data);
    }

    /** Sets whether QQ should send the media as a message after upload. */
    public RichMediaRequest serverSendsMessage(boolean enabled) {
        body.put("srv_send_msg", enabled);
        return this;
    }

    /** Adds or replaces a string field. */
    public RichMediaRequest put(String field, String value) {
        body.put(field, value);
        return this;
    }

    /** Adds or replaces a numeric field. */
    public RichMediaRequest put(String field, long value) {
        body.put(field, value);
        return this;
    }

    /** Returns a defensive JSON copy of this request. */
    public JsonNode toJson() {
        return body.deepCopy();
    }
}
