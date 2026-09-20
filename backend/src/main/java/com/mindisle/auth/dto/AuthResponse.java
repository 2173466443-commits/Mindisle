package com.mindisle.auth.dto;

/**
 * 认证成功响应：令牌对 + 前端立刻需要的最小用户信息。
 *
 * <p>expiresIn 单位秒，前端据此决定提前多久静默刷新；user 里永不出现 password（实体上已 @JsonIgnore，
 * 这里再用显式白名单字段第二重保证「出参不携带凭据」）。
 */
public record AuthResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,
    UserBrief user
) {

  /** 用户摘要，前端 Pinia userStore 直接整体替换。 */
  public record UserBrief(
      Long id,
      String username,
      String nickname,
      String avatar,
      String role,
      String aiStyle,
      String status
  ) {}
}
