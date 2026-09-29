package com.mindisle.web;

import java.time.LocalDateTime;

import com.mindisle.admin.AdminOpLogService.Ctx;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.ratelimit.RateLimitInterceptor;
import com.mindisle.security.AuthUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理端控制器的公共姿势（阶段 6 · 手册 §9.1）。
 *
 * <p>只做两件事：把「当前登录身份 + 请求上下文」翻译成服务层的 {@code Ctx}，以及把「必须管理员」
 * 这条判定收敛到一处。服务层从阶段 2 起就不碰 Servlet API（为了单测不需要造 MockHttpServletRequest），
 * 这条边界不能因为管理端接口多就破例——否则八个控制器各写一遍取 IP 的代码，
 * 而审计日志里的 ip 字段一旦口径不一，FR8.4「解匿 100% 留痕」就成了看起来有留痕、实际上没法复核。</p>
 */
final class AdminSupport {

  private AdminSupport() {
  }

  /** 鉴权闸门：SecurityConfig 已经挡在 URL 层，这里再判一次是防白名单被误改（与 AuditController 同口径）。 */
  static long requireAdmin(AuthUser current) {
    if (current == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    if (!current.isAdmin()) {
      throw new BizException(ErrorCode.FORBIDDEN, "管理端接口仅限 ADMIN / SUPER");
    }
    return current.id();
  }

  /** 身份上下文：操作人、角色、来源 IP、客户端串——四个字段一起进 admin_op_log。 */
  static Ctx ctxOf(AuthUser current, HttpServletRequest http) {
    requireAdmin(current);
    return new Ctx(current.id(), current.role(),
        http == null ? null : RateLimitInterceptor.clientIp(http),
        http == null ? null : http.getHeader("User-Agent"));
  }

  /** 统一时间源：所有状态流转与 SLA 计算都用同一个 now，便于日志里对账。 */
  static LocalDateTime now() {
    return LocalDateTime.now();
  }
}