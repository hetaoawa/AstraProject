package top.hetaoawa.qqbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record QQMessageScene(String source, List<String> extensions, JsonNode raw) {
    public String extension(String key) {
        String prefix = key + "=";
        return extensions.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }
}
