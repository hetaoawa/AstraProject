package top.hetaoawa.qqbot;

/** QQ 官方 API 定义的 Gateway Intent 位标记。 */
public final class Intents {
    private Intents() {
    }

    /** Guild 生命周期事件。 */
    public static final long GUILDS = 1L << 0;
    /** Guild 成员事件。 */
    public static final long GUILD_MEMBERS = 1L << 1;
    /** Guild 消息事件。 */
    public static final long GUILD_MESSAGES = 1L << 9;
    /** Guild 消息 Reaction 事件。 */
    public static final long GUILD_MESSAGE_REACTIONS = 1L << 10;
    /** 私聊事件。 */
    public static final long DIRECT_MESSAGE = 1L << 12;
    /** 群聊和 C2C 消息事件。 */
    public static final long GROUP_AND_C2C_EVENT = 1L << 25;
    /** 互动事件。 */
    public static final long INTERACTION = 1L << 26;
    /** 消息审核事件。 */
    public static final long MESSAGE_AUDIT = 1L << 27;
    /** 论坛事件。 */
    public static final long FORUMS_EVENT = 1L << 28;
    /** 音频操作事件。 */
    public static final long AUDIO_ACTION = 1L << 29;
    /** 公域 Guild 消息事件。 */
    public static final long PUBLIC_GUILD_MESSAGES = 1L << 30;

    /** 默认订阅范围：群聊和 C2C 消息事件。 */
    public static final long PRIVATE_AND_GROUP = GROUP_AND_C2C_EVENT;
}
