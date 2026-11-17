package top.hetaoawa.astra.api;

/** Current C2C user information. A missing proactive-message preference remains unknown. */
public record UserInfo(
    String unionOpenid,
    String username,
    String avatar,
    Boolean allowProactiveMsg,
    ApiResponse response) {
  public static UserInfo from(ApiResponse response) {
    var data = response.data();
    return new UserInfo(
        text(data.get("union_openid")),
        text(data.get("username")),
        text(data.get("avatar")),
        data.get("allow_proactive_msg") instanceof Boolean value ? value : null,
        response);
  }

  private static String text(Object value) {
    return value instanceof String text ? text : null;
  }
}
