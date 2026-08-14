package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** QQ 接受消息或互动响应后返回的结果。
 * @param id QQ 返回的消息或响应 ID
 * @param timestamp QQ 返回的时间戳
 * @param raw QQ 原始 JSON 响应
 */
public record MessageResponse(String id, String timestamp, JsonNode raw) {
}
