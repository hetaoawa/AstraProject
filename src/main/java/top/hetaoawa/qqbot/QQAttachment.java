package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** Attachment metadata delivered with a message. */
/** Attachment metadata included in a normalized QQ message. */
public record QQAttachment(
        String url,
        String filename,
        String contentType,
        Integer width,
        Integer height,
        Long size,
        String voiceWavUrl,
        String asrReferText,
        JsonNode raw
) {
}
