package top.hetaoawa.qqbot;

import java.util.List;

/** 从消息中解析出的命令调用，提供命令、参数和原始消息访问能力。 */
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

    /** 返回原始标准化消息事件。 */
    public QQMessageEvent message() {
        return message;
    }

    /** 返回匹配消息的命令前缀。 */
    public String prefix() {
        return prefix;
    }

    /** 返回匹配到的命令名称。 */
    public String command() {
        return command;
    }

    /** 返回按配置分隔符拆分后的命令及全部 Token。 */
    public List<String> tokens() {
        return tokens;
    }

    /** 返回命令名称之后的全部参数。 */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * 按从零开始的下标返回一个参数。
     *
     * @param index 参数下标
     * @return 参数文本
     * @throws IndexOutOfBoundsException 下标超出 {@link #arguments()} 范围
     */
    public String argument(int index) {
        return arguments.get(index);
    }

    /** 返回原始消息文本。 */
    public String content() {
        return message.content();
    }

    /** 返回消息作者的 User OpenID。 */
    public String userOpenId() {
        return message.userOpenId();
    }

    /** 返回消息所属群的 OpenID；私聊消息返回 {@code null}。 */
    public String groupOpenId() {
        return message.groupOpenId();
    }

    /** 向触发命令的消息发送文本回复。 */
    public MessageResponse replyText(String content) {
        return message.replyText(content);
    }
}
