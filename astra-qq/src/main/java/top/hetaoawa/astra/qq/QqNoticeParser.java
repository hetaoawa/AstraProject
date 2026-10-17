package top.hetaoawa.astra.qq;

import java.util.Map;
import top.hetaoawa.astra.api.NoticePayload;

final class QqNoticeParser {
  static NoticePayload parse(String type, Map<String, Object> raw) {
    Class<? extends NoticePayload> model =
        switch (type) {
          case "GUILD_CREATE", "GUILD_UPDATE", "GUILD_DELETE" -> NoticePayload.Guild.class;
          case "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE" -> NoticePayload.Channel.class;
          case "FRIEND_ADD", "FRIEND_DEL" -> NoticePayload.Friend.class;
          case "C2C_MSG_RECEIVE", "C2C_MSG_REJECT" -> NoticePayload.C2cPermission.class;
          case "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT" ->
              NoticePayload.GroupBotState.class;
          case "INTERACTION_CREATE" -> NoticePayload.Interaction.class;
          case "SUBSCRIBE_MESSAGE_STATUS" -> NoticePayload.Subscription.class;
          case "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE" -> NoticePayload.Reaction.class;
          case "MESSAGE_AUDIT_PASS", "MESSAGE_AUDIT_REJECT" -> NoticePayload.Audit.class;
          case "AUDIO_START", "AUDIO_FINISH", "AUDIO_ON_MIC", "AUDIO_OFF_MIC" ->
              NoticePayload.Audio.class;
          case "AUDIO_OR_LIVE_CHANNEL_MEMBER_ENTER", "AUDIO_OR_LIVE_CHANNEL_MEMBER_EXIT" ->
              NoticePayload.AudioMember.class;
          case "FORUM_THREAD_CREATE",
                  "FORUM_THREAD_UPDATE",
                  "FORUM_THREAD_DELETE",
                  "FORUM_POST_CREATE",
                  "FORUM_POST_DELETE",
                  "FORUM_REPLY_CREATE",
                  "FORUM_REPLY_DELETE",
                  "OPEN_FORUM_THREAD_CREATE",
                  "OPEN_FORUM_THREAD_UPDATE",
                  "OPEN_FORUM_THREAD_DELETE",
                  "OPEN_FORUM_POST_CREATE",
                  "OPEN_FORUM_POST_DELETE",
                  "OPEN_FORUM_REPLY_CREATE",
                  "OPEN_FORUM_REPLY_DELETE" ->
              NoticePayload.Forum.class;
          case "FORUM_PUBLISH_AUDIT_RESULT", "OPEN_FORUM_PUBLISH_AUDIT_RESULT" ->
              NoticePayload.ForumAudit.class;
          default -> null;
        };
    return model == null ? null : QqTypedData.read(model, raw);
  }

  static Class<? extends NoticePayload> storedType(String kind) {
    for (Class<?> c : NoticePayload.class.getPermittedSubclasses())
      if (c.getSimpleName().equals(kind)) return c.asSubclass(NoticePayload.class);
    throw new IllegalArgumentException("Unknown queued notice model: " + kind);
  }
}
