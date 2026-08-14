package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 消息附带的场景元数据和扩展字段。
 * @param source 消息场景来源
 * @param extensions 原始扩展字段列表
 * @param raw 原始场景 JSON
 */
public record QQMessageScene(String source, List<String> extensions, JsonNode raw) {
    /** 返回 {@code key=value} 格式扩展字段的值；不存在时返回 {@code null}。 */
    public String extension(String key) {
        String prefix = key + "=";
        return extensions.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }
}
