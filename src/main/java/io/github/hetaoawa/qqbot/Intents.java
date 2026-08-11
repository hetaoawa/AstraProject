package io.github.hetaoawa.qqbot;

/** QQ Bot Gateway intent bit masks from the official API. */
public final class Intents {
    private Intents() {
    }

    public static final long GUILDS = 1L << 0;
    public static final long GUILD_MEMBERS = 1L << 1;
    public static final long GUILD_MESSAGES = 1L << 9;
    public static final long GUILD_MESSAGE_REACTIONS = 1L << 10;
    public static final long DIRECT_MESSAGE = 1L << 12;
    public static final long GROUP_AND_C2C_EVENT = 1L << 25;
    public static final long INTERACTION = 1L << 26;
    public static final long MESSAGE_AUDIT = 1L << 27;
    public static final long FORUMS_EVENT = 1L << 28;
    public static final long AUDIO_ACTION = 1L << 29;
    public static final long PUBLIC_GUILD_MESSAGES = 1L << 30;

    /** The first-round default: private and group/C2C events, without channel events. */
    public static final long PRIVATE_AND_GROUP = GROUP_AND_C2C_EVENT;
}
