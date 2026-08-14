package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** 标准化 QQ 消息中的附件元数据。
 * @param url 附件 URL
 * @param filename 文件名
 * @param contentType MIME 类型
 * @param width 图片或视频宽度
 * @param height 图片或视频高度
 * @param size 文件大小
 * @param voiceWavUrl 语音 WAV 地址
 * @param asrReferText 语音识别文本
 * @param raw 原始 JSON 节点
 */
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
