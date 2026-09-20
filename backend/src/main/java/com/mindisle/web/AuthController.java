package com.mindisle.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import com.mindisle.auth.AuthService;
import com.mindisle.auth.dto.AuthResponse;
import com.mindisle.auth.dto.LoginRequest;
import com.mindisle.auth.dto.RefreshRequest;
import com.mindisle.auth.dto.RegisterRequest;
import com.mindisle.captcha.CaptchaService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.ratelimit.RateLimitInterceptor;
import com.mindisle.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 认证接口（手册 §5.7 T2.9 · 需求 FR1.1、FR1.2、FR1.4 · 任务 T2.16）。
 *
 * <p>本前缀整体落在 SecurityConfig 的 permitAll 白名单里，因此这几个接口拿不到
 * 框架的「未登录自动 401」兜底，logout 必须自己判空 —— 见方法内注释。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "1 认证", description = "图形验证码、注册、登录、令牌刷新与注销")
public class AuthController {

  private final AuthService authService;
  private final CaptchaService captchaService;

  public AuthController(AuthService authService, CaptchaService captchaService) {
    this.authService = authService;
    this.captchaService = captchaService;
  }

  /**
   * 取一张一次性图形验证码。
   *
   * <p>imageBase64 不含 data: 前缀（手册 §5.11 T2.16 第 1 条），前端拼
   * src="data:image/png;base64," 即可渲染；只有文本进缓存，图片不落库。
   */
  @GetMapping("/captcha")
  @Operation(summary = "获取图形验证码")
  public Result<CaptchaView> captcha() {
    CaptchaService.Captcha captcha = captchaService.generate();
    return Result.ok(new CaptchaView(captcha.captchaId(), captcha.imageBase64(), captcha.ttlSeconds()));
  }

  @PostMapping("/register")
  @Operation(summary = "注册（协议双同意 + 敏感信息单独同意留痕）")
  public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
    return Result.ok(authService.register(req, RateLimitInterceptor.clientIp(http), http.getHeader("User-Agent")));
  }

  @PostMapping("/login")
  @Operation(summary = "登录（连错锁定，锁定与口令错误同一口径）")
  public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    return Result.ok(authService.login(req, RateLimitInterceptor.clientIp(http)));
  }

  @PostMapping("/refresh")
  @Operation(summary = "用 refreshToken 换新令牌对")
  public Result<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
    return Result.ok(authService.refresh(req.refreshToken()));
  }

  /**
   * 注销：吊销服务端令牌白名单。
   *
   * <p>/api/auth/** 是 permitAll，Spring Security 不会替我们挡未登录请求，
   * 所以这里显式判 principal 为空并抛 10002。这样做的第二个好处是：
   * 即使将来有人调整白名单顺序，注销接口的语义也不会变。
   */
  @PostMapping("/logout")
  @Operation(summary = "注销当前会话")
  public Result<Void> logout(@AuthenticationPrincipal AuthUser user) {
    if (user == null || user.id() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    authService.logout(user.id());
    return Result.ok();
  }

  /** 验证码出参：captchaId 随表单回传，ttlSeconds 让前端能在过期前主动刷新。 */
  public record CaptchaView(String captchaId, String imageBase64, int ttlSeconds) {}
}
