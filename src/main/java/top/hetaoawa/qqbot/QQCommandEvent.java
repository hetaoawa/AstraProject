package top.hetaoawa.qqbot;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A normalized command invocation parsed from a message. */
public final class QQCommandEvent {
    private final QQMessageEvent message;
    private final String prefix;
    private final String command;
    private final List<String> tokens;
    private final List<String> arguments;

    QQCommandEvent(QQMessageEvent message, String prefix, List<String> tokens) {
        this.message = message;
        this.prefix = prefix;
        this.tokens = List.copyOf(tokens);
        this.command = tokens.get(0);
        this.arguments = List.copyOf(tokens.subList(1, tokens.size()));
    }

    public QQMessageEvent message() {
        return message;
    }

    public String prefix() {
        return prefix;
    }

    public String command() {
        return command;
    }

    /** Returns the command and all arguments after splitting by the configured separator. */
    public List<String> tokens() {
        return tokens;
    }

    public List<String> arguments() {
        return arguments;
    }

    public String argument(int index) {
        return arguments.get(index);
    }

    public String content() {
        return message.content();
    }

    public String userOpenId() {
        return message.userOpenId();
    }

    public String groupOpenId() {
        return message.groupOpenId();
    }

    public CompletableFuture<MessageResponse> replyText(String content) {
        return message.replyText(content);
    }
}
