package com.mindisle.security;

/**
 * 当前登录身份（手册 §5.6 RBAC）。
 *
 * <p>不用 Spring 的 UserDetails 包装，是为了保持「JWT 声明 → 业务身份」这条链路上
 * 没有多余抽象层；Controller 里用 @AuthenticationPrincipal AuthUser user 直接取。</p>
 *
 * @param id       user.id
 * @param username user.username
 * @param role     USER / ADMIN / SUPER（与 user.role 的 ENUM 取值一致）
 * @param tokenId  JWT 的 jti，用于 user:token:{id} 白名单比对与强制下线（FR1.2）
 */
public record AuthUser(Long id, String username, String role, String tokenId) {

    /** RBAC 判定：管理类接口一律要求 ADMIN 或 SUPER。 */
    public boolean isAdmin() {
        return "ADMIN".equals(role) || "SUPER".equals(role);
    }
}
