package com.mindisle.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.auth.AuthService;
import com.mindisle.auth.dto.AuthResponse;
import com.mindisle.auth.dto.LoginRequest;
import com.mindisle.common.Result;
import com.mindisle.ratelimit.RateLimitInterceptor;
import com.mindisle.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 管理端登录（手册 §5.7 T2.12 · 需求 FR4 角色边界）。
 *
 * <p>路径拆分有意为之：只有 /api/admin/auth/login 在 permitAll 白名单里，/api/admin 前缀下的
 * 其余接口全部要求 ADMIN 或 SUPER 角色（在 SecurityConfig 里集中声明，不靠方法注解兜底）。
 * 管理员登录复用同一套账号表与同一套锁定策略，靠 user.role 判定能否进后台，
 * 避免出现「第二套口令」这种最容易被忽略的后门。</p>
 *
 * <p><b>本类只剩登录一件事</b>：阶段 2 立骨架时这里挂了三个 notImplemented 壳
 * （/stats/overview、/audit/tasks、/configs），阶段 6 的真实现落地后必须删掉——
 * 留着会和 AdminAuditController 的 GET /api/admin/audit/tasks、AdminConfigController 的
 * GET /api/admin/configs 撞成 ambiguous mapping，应用直接起不来。壳的使命是「让前端先有 mock 可打」，
 * 真接口上线即拆，这是骨架代码的到期日。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "5 管理端-登录", description = "管理员登录（其余管理端接口见同前缀下的各业务控制器）")
public class AdminController {

  private final AuthService authService;

  public AdminController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/auth/login")
  @Operation(summary = "管理员登录（非 ADMIN 或 SUPER 角色直接拒绝）")
  public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    return Result.ok(authService.loginAsAdmin(req, RateLimitInterceptor.clientIp(http)));
  }

  /**
   * 刷新页面后恢复管理员身份（A6/A9 的角色相关控件靠它，不靠前端缓存）。
   *
   * <p>路径为什么是 /api/admin/me 而不是 /api/admin/auth/me：<b>这是本项目踩过一次的那类坑</b>——
   * JwtAuthFilter 的 ANONYMOUS_PREFIXES 里挂着 "/api/admin/auth/"（为了免登录放行登录接口本身），
   * 凡是落在这条前缀下的新端点都会<b>被跳过解析令牌</b>，@AuthenticationPrincipal 永远是 null，
   * 于是这个接口只能返回 10002，而它看起来「和白名单里那条登录接口只差一个词」。
   * 与 /api/topics 那次同构，判据同条：往带尾斜杠的匿名前缀里加接口 = 自毁鉴权。</p>
   */
  @GetMapping("/me")
  @Operation(summary = "回读当前管理员身份（昵称/角色/账号态），前端刷新后重建角色判定")
  public Result<AuthResponse.UserBrief> me(@AuthenticationPrincipal AuthUser current) {
    return Result.ok(authService.briefOf(AdminSupport.requireAdmin(current)));
  }
}