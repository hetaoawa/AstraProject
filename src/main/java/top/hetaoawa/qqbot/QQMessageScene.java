package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Scene metadata and extension fields attached to a message. */
public record QQMessageScene(String source, List<String> extensions, JsonNode raw) {
    /** Returns the value of an extension encoded as {@code key=value}, or {@code null}. */
    public String extension(String key) {
        String prefix = key + "=";
        return extensions.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }
}
