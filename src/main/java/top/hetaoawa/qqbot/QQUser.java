package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** 从事件载荷中标准化得到的用户或机器人身份。
 * @param id 用户 ID
 * @param username 用户名
 * @param bot 是否为机器人
 * @param userOpenId 用户 OpenID
 * @param memberOpenId 成员 OpenID
 * @param memberRole 成员角色
 * @param raw 原始用户 JSON
 */
public record QQUser(
        String id,
        String username,
        boolean bot,
        String userOpenId,
        String memberOpenId,
        String memberRole,
        JsonNode raw
) {
}
