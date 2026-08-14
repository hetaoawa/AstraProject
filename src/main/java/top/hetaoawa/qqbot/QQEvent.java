package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

/** WebSocket 和 Webhook 共用的原始事件封装。
 * @param id 事件 ID
 * @param op Gateway 操作码
 * @param sequence Gateway 序列号
 * @param type 事件类型
 * @param data 解析后的数据节点
 * @param raw 原始事件 JSON
 */
public record QQEvent(
        String id,
        int op,
        Long sequence,
        String type,
        JsonNode data,
        JsonNode raw
) {
    /** 判断该事件是否为普通 Gateway Dispatch 事件。 */
    public boolean isDispatch() {
        return op == 0;
    }
}
