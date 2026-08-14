package top.hetaoawa.qqbot;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A normalized command invocation parsed from a message. */
/** Parsed command invocation delivered to a command listener. */
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

    /** Returns the original normalized message event. */
    public QQMessageEvent message() {
        return message;
    }

    /** Returns the prefix that matched the message. */
    public String prefix() {
        return prefix;
    }

    /** Returns the matched command name. */
    public String command() {
        return command;
    }

    /** Returns the command and all arguments after splitting by the configured separator. */
    /** Returns the command and all parsed tokens. */
    public List<String> tokens() {
        return tokens;
    }

    /** Returns all tokens after the command name. */
    public List<String> arguments() {
        return arguments;
    }

    /** Returns an argument by zero-based index. */
    public String argument(int index) {
        return arguments.get(index);
    }

    /** Returns the original message content. */
    public String content() {
        return message.content();
    }

    /** Returns the message author's user OpenID. */
    public String userOpenId() {
        return message.userOpenId();
    }

    /** Returns the message's group OpenID, when present. */
    public String groupOpenId() {
        return message.groupOpenId();
    }

    /** Sends a text reply to the command message. */
    public CompletableFuture<MessageResponse> replyText(String content) {
        return message.replyText(content);
    }
}
