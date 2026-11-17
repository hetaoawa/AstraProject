package top.hetaoawa.astra.api;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface PlatformApi {
  /** Queries the user's C2C profile; the platform requires an existing friend relationship. */
  default CompletionStage<UserInfo> userInfo(String userOpenid) {
    if (userOpenid == null || userOpenid.isBlank())
      throw new IllegalArgumentException("Nonempty user_openid required");
    return call(
            "v2_users_user_openid_info.get", Map.of("user_openid", userOpenid), Map.of(), Map.of())
        .thenApply(UserInfo::from);
  }

  default Moderation moderation() {
    return new Moderation(this);
  }

  default CompletionStage<ApiResponse> call(
      String operation, Map<String, String> path, Map<String, Object> query, RequestPayload body) {
    return call(operation, path, query, body.toMap());
  }

  java.util.concurrent.CompletionStage<ApiResponse> multipart(
      String method,
      String relativePath,
      java.util.Map<String, String> fields,
      java.util.Map<String, UploadFile> files);

  Map<String, ApiOperation> operations();

  CompletionStage<ApiResponse> call(
      String operation,
      Map<String, String> path,
      Map<String, Object> query,
      Map<String, Object> body);

  CompletionStage<ApiResponse> raw(
      String method, String relativePath, Map<String, Object> query, Map<String, Object> body);

  CompletionStage<ApiResponse> uploadPart(
      String uploadUrl, byte[] bytes, Map<String, String> headers);
}
