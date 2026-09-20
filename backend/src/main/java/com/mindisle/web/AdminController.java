package com.mindisle.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.auth.AuthService;
import com.mindisle.auth.dto.AuthResponse;
import com.mindisle.auth.dto.LoginRequest;
import com.mindisle.common.BizException;
import com.mindisle.common.Result;
import com.mindisle.ratelimit.RateLimitInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 管理端接口（手册 §5.7 T2.12 · 需求 FR4 内容审核、FR5 运营看板、§12 规范 4）。
 *
 * <p>路径拆分有意为之：只有 /api/admin/auth/login 在 permitAll 白名单里，/api/admin 前缀下的
 * 其余接口全部要求 ADMIN 或 SUPER 角色（在 SecurityConfig 里集中声明，不靠方法注解兜底）。
 * 管理员登录复用同一套账号表与同一套锁定策略，靠 user.role 判定能否进后台，
 * 避免出现「第二套口令」这种最容易被忽略的后门。
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "5 管理端", description = "管理员登录、运营看板、审核队列与参数配置")
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

  @GetMapping("/stats/overview")
  @Operation(summary = "运营看板总览（阶段 5 实现）")
  public Result<Void> overview() {
    throw BizException.notImplemented("阶段5 运营看板统计");
  }

  @GetMapping("/audit/tasks")
  @Operation(summary = "审核任务队列（阶段 5 实现）")
  public Result<Void> auditTasks() {
    throw BizException.notImplemented("阶段5 内容审核队列");
  }

  @GetMapping("/configs")
  @Operation(summary = "系统参数管理（阶段 5 实现，公开读取走 /api/system/configs）")
  public Result<Void> configs() {
    throw BizException.notImplemented("阶段5 系统参数增删改");
  }
}
