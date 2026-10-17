package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Known QQ notices. Nullable fields remain unknown; raw preserves future fields. */
public sealed interface NoticePayload {
  record Guild(
      String id,
      String name,
      String icon,
      String ownerId,
      Integer memberCount,
      Integer maxMembers,
      String description,
      Instant joinedAt,
      String opUserId,
      Map<String, Object> raw)
      implements NoticePayload {}

  record Channel(
      String id,
      String guildId,
      String name,
      Integer type,
      Integer subType,
      Integer position,
      String parentId,
      String ownerId,
      Integer privateType,
      Integer speakPermission,
      String applicationId,
      String permissions,
      String opUserId,
      Map<String, Object> raw)
      implements NoticePayload {}

  record Friend(
      Long timestamp,
      String openid,
      Integer scene,
      String sceneParam,
      UnionAuthor author,
      String shortCode,
      Map<String, Object> raw)
      implements NoticePayload {}

  record UnionAuthor(String unionOpenid, String unionUserAccount, Map<String, Object> raw) {}

  record C2cPermission(Long timestamp, String openid, Map<String, Object> raw)
      implements NoticePayload {}

  record GroupBotState(
      Long timestamp, String groupOpenid, String opMemberOpenid, Map<String, Object> raw)
      implements NoticePayload {}

  record Interaction(
      String id,
      Integer type,
      String scene,
      Integer chatType,
      Instant timestamp,
      String guildId,
      String channelId,
      String userOpenid,
      String groupOpenid,
      String groupMemberOpenid,
      InteractionData data,
      Integer version,
      String applicationId,
      Map<String, Object> raw)
      implements NoticePayload {}

  record InteractionData(Integer type, Resolved resolved, Map<String, Object> raw) {}

  record Resolved(
      String buttonData,
      String buttonId,
      String userId,
      String featureId,
      String messageId,
      String feedbackOpt,
      Integer checked,
      String action,
      InteractionScene messageScene,
      AuthorizeData authorizeData,
      Map<String, Object> raw) {}

  record InteractionScene(List<String> ext, Map<String, Object> raw) {}

  record AuthorizeData(String optScene, String scope, Map<String, Object> raw) {}

  record Subscription(
      String groupOpenid, String openid, List<SubscriptionResult> result, Map<String, Object> raw)
      implements NoticePayload {}

  record SubscriptionResult(
      Integer templateId,
      String customTemplateId,
      Integer op,
      String subscribeId,
      Long subscribeTs,
      Long updateTs,
      Map<String, Object> raw) {}

  record Reaction(
      String userId,
      String guildId,
      String channelId,
      ReactionTarget target,
      Emoji emoji,
      Map<String, Object> raw)
      implements NoticePayload {}

  record ReactionTarget(String id, Integer type, Map<String, Object> raw) {}

  record Emoji(String id, Integer type, Map<String, Object> raw) {}

  record Audit(
      String auditId,
      String messageId,
      String guildId,
      String channelId,
      Instant auditTime,
      Instant createTime,
      Map<String, Object> raw)
      implements NoticePayload {}

  record Audio(
      String guildId, String channelId, String audioUrl, String text, Map<String, Object> raw)
      implements NoticePayload {}

  record AudioMember(
      String guildId, String channelId, Integer channelType, String userId, Map<String, Object> raw)
      implements NoticePayload {}

  record Forum(
      String guildId,
      String channelId,
      String authorId,
      ThreadInfo threadInfo,
      PostInfo postInfo,
      ReplyInfo replyInfo,
      Map<String, Object> raw)
      implements NoticePayload {}

  record ThreadInfo(
      String threadId, String title, String content, Instant dateTime, Map<String, Object> raw) {}

  record PostInfo(
      String threadId, String postId, String content, Instant dateTime, Map<String, Object> raw) {}

  record ReplyInfo(
      String threadId,
      String postId,
      String replyId,
      String content,
      Instant dateTime,
      Map<String, Object> raw) {}

  record ForumAudit(
      String guildId,
      String channelId,
      String authorId,
      String threadId,
      String postId,
      String replyId,
      Integer type,
      Integer result,
      String errMsg,
      Map<String, Object> raw)
      implements NoticePayload {}
}
