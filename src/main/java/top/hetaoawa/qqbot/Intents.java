package top.hetaoawa.qqbot;

/** QQ Bot Gateway intent bit masks from the official API. */
/** Bit flags used to select Gateway events delivered to the bot. */
public final class Intents {
    private Intents() {
    }

    /** Guild lifecycle events. */
    public static final long GUILDS = 1L << 0;
    /** Guild member events. */
    public static final long GUILD_MEMBERS = 1L << 1;
    /** Guild message events. */
    public static final long GUILD_MESSAGES = 1L << 9;
    /** Guild message reaction events. */
    public static final long GUILD_MESSAGE_REACTIONS = 1L << 10;
    /** Direct-message events. */
    public static final long DIRECT_MESSAGE = 1L << 12;
    /** Group and C2C message events. */
    public static final long GROUP_AND_C2C_EVENT = 1L << 25;
    /** Interaction events. */
    public static final long INTERACTION = 1L << 26;
    /** Message audit events. */
    public static final long MESSAGE_AUDIT = 1L << 27;
    /** Forum events. */
    public static final long FORUMS_EVENT = 1L << 28;
    /** Audio action events. */
    public static final long AUDIO_ACTION = 1L << 29;
    /** Public guild message events. */
    public static final long PUBLIC_GUILD_MESSAGES = 1L << 30;

    /** The first-round default: private and group/C2C events, without channel events. */
    public static final long PRIVATE_AND_GROUP = GROUP_AND_C2C_EVENT;
}
