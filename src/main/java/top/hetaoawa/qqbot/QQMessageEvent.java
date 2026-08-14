package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Normalized private/group message event with reply helpers. */
public final class QQMessageEvent {
    private final QQBot bot;
    private final String eventId;
    private final String eventType;
    private final String messageId;
    private final QQUser author;
    private final String content;
    private final String userOpenId;
    private final String groupOpenId;
    private final Integer messageType;
    private final Instant timestamp;
    private final QQMessageScene scene;
    private final List<QQAttachment> attachments;
    private final JsonNode raw;

    private QQMessageEvent(QQBot bot, String eventId, String eventType, JsonNode data, JsonNode raw) {
        this.bot = bot;
        this.eventId = eventId;
        this.eventType = eventType;
        this.raw = raw;
        this.messageId = text(data, "id");
        JsonNode authorNode = data.path("author");
        this.author = new QQUser(
                text(authorNode, "id"),
                text(authorNode, "username"),
                authorNode.path("bot").asBoolean(false),
                text(authorNode, "user_openid"),
                text(authorNode, "member_openid"),
                text(authorNode, "member_role"),
                authorNode
        );
        this.content = text(data, "content");
        this.userOpenId = firstNonBlank(text(data, "user_openid"), text(authorNode, "user_openid"));
        this.groupOpenId = text(data, "group_openid");
        this.messageType = data.has("message_type") && data.get("message_type").canConvertToInt()
                ? data.get("message_type").asInt() : null;
        this.timestamp = parseInstant(text(data, "timestamp"));
        JsonNode sceneNode = data.path("message_scene");
        List<String> extensions = new ArrayList<>();
        if (sceneNode.path("ext").isArray()) {
            sceneNode.path("ext").forEach(node -> extensions.add(node.asText()));
        }
        this.scene = new QQMessageScene(text(sceneNode, "source"), List.copyOf(extensions), sceneNode);
        List<QQAttachment> attachmentList = new ArrayList<>();
        if (data.path("attachments").isArray()) {
            data.path("attachments").forEach(node -> attachmentList.add(new QQAttachment(
                    text(node, "url"),
                    text(node, "filename"),
                    text(node, "content_type"),
                    integer(node, "width"),
                    integer(node, "height"),
                    longValue(node, "size"),
                    text(node, "voice_wav_url"),
                    text(node, "asr_refer_text"),
                    node
            )));
        }
        this.attachments = List.copyOf(attachmentList);
    }

    static QQMessageEvent from(QQBot bot, QQEvent event) {
        return new QQMessageEvent(bot, event.id(), event.type(), event.data(), event.raw());
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String messageId() {
        return messageId;
    }

    public QQUser author() {
        return author;
    }

    public String content() {
        return content;
    }

    public String userOpenId() {
        return userOpenId;
    }

    public String groupOpenId() {
        return groupOpenId;
    }

    public Integer messageType() {
        return messageType;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public QQMessageScene scene() {
        return scene;
    }

    public List<QQAttachment> attachments() {
        return attachments;
    }

    public JsonNode raw() {
        return raw;
    }

    public boolean isGroupMessage() {
        return groupOpenId != null && !groupOpenId.isBlank();
    }

    public java.util.concurrent.CompletableFuture<MessageResponse> replyText(String content) {
        return bot.replyText(this, content);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
