package com.mindisle.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mindisle.auth.dto.AuthResponse.UserBrief;
import com.mindisle.auth.dto.ConsentRequest;
import com.mindisle.common.BizException;
import com.mindisle.common.Result;
import com.mindisle.entity.UserConsent;
import com.mindisle.ratelimit.RateLimitInterceptor;
import com.mindisle.security.AuthUser;
import com.mindisle.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 「我的」接口（手册 §5.7 T2.10 · 需求 FR1.3、FR1.10、§12 规范 3）。
 *
 * <p>本前缀不在 SecurityConfig 白名单里，未登录会被过滤器链直接挡成 401，
 * 因此可以直接信任 @AuthenticationPrincipal 非空。第二个好处是：越权读别人的资料
 * 在这条路上根本构造不出来 —— 路径里没有 id 参数，userId 只能来自令牌。
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "3 用户", description = "账号摘要、扩展资料与隐私授权开关")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/me")
  @Operation(summary = "当前登录者账号摘要（前端刷新后恢复登录态）")
  public Result<UserBrief> me(@AuthenticationPrincipal AuthUser user) {
    return Result.ok(userService.me(user.id()));
  }

  @GetMapping("/me/profile")
  @Operation(summary = "当前登录者扩展资料（刻意不含 risk_flag）")
  public Result<UserService.ProfileView> profile(@AuthenticationPrincipal AuthUser user) {
    return Result.ok(userService.profile(user.id()));
  }

  @PutMapping("/me/profile")
  @Operation(summary = "修改扩展资料（阶段 3 实现）")
  public Result<Void> updateProfile(@AuthenticationPrincipal AuthUser user,
                                    @RequestBody(required = false) Object body) {
    throw BizException.notImplemented("阶段3 资料编辑与头像上传");
  }

  @GetMapping("/me/consents")
  @Operation(summary = "我的授权流水（含已撤回历史，用于举证）")
  public Result<List<UserConsent>> consents(@AuthenticationPrincipal AuthUser user) {
    return Result.ok(userService.listConsents(user.id()));
  }

  @PostMapping("/me/consents")
  @Operation(summary = "授予或撤回某类授权（写留痕与同步冗余开关同事务）")
  public Result<UserConsent> grant(@AuthenticationPrincipal AuthUser user,
                                   @Valid @RequestBody ConsentRequest req,
                                   HttpServletRequest http) {
    return Result.ok(userService.grantOrWithdraw(user.id(), req,
        RateLimitInterceptor.clientIp(http), http.getHeader("User-Agent")));
  }
}
