package top.hetaoawa.astra.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** All currently documented GROUP_JOIN_REQUEST fields; absent values remain unknown. */
public record GroupJoinRequest(
    String groupOpenid,
    String joinRequestId,
    String riskTips,
    String unionOpenid,
    String memberOpenid,
    String username,
    Instant applyAt,
    String applySource,
    String invitedBy,
    Boolean bot,
    Verification verifyInfo,
    AutoApproved autoApproved,
    PlatformMember applicant,
    PlatformMember inviter,
    PlatformMember operator,
    Map<String, Object> raw) {
  public record Verification(String method, String verifyMessage, List<ReviewQA> reviewQaList) {
    public Verification {
      reviewQaList = List.copyOf(reviewQaList);
    }
  }

  public record ReviewQA(String question, String answer) {}

  public record AutoApproved(String strategyId, Map<String, Object> raw) {}
}
