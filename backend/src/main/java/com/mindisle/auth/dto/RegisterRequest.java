package com.mindisle.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册入参（需求 FR1.1 / §12 规范 3「协议同意留痕」）。
 *
 * <p>两个必须勾选的同意项用 @AssertTrue 直接挡在 Controller 入口，不放进 service，
 * 这样任何绕过 service 的调用（例如将来加批量导入工具）也不会漏掉留痕逻辑。
 *
 * <p>验证码是必填项：注册接口是垃圾账号的主要入口，不能只靠 IP 限流。
 */
public record RegisterRequest(

    @NotBlank(message = "请填写用户名")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "用户名只能为 4-32 位字母、数字或下划线")
    String username,

    @NotBlank(message = "请填写密码")
    @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间")
    String password,

    @Size(max = 32, message = "昵称最长 32 字")
    String nickname,

    @NotBlank(message = "请填写验证码")
    String captchaId,

    @NotBlank(message = "请填写验证码")
    @Size(max = 8)
    String captchaCode,

    @AssertTrue(message = "请阅读并同意用户协议")
    Boolean agreeTerms,

    @AssertTrue(message = "请阅读并同意隐私政策")
    Boolean agreePrivacy,

    /** 敏感个人信息（性别、年级等）单独同意，可选；不勾也能注册成功。 */
    Boolean agreeSensitive,

    /** 协议版本号，前端从 /api/system/configs 取当前值回传，落库用于举证。 */
    @Size(max = 16)
    String consentVersion,

    /** 必须是 user_profile.grade 的 ENUM 值（FRESH/SOPH/JUNIOR/SENIOR/OTHER），不填=OTHER。 */
    @Pattern(regexp = "^$|^(FRESH|SOPH|JUNIOR|SENIOR|OTHER)$", message = "年级取值不合法，请重新选择")
    @Size(max = 16)
    String grade,

    @Size(max = 64)
    String school,

    @Size(max = 32)
    String regSource
) {

  /** @AssertTrue 对 null 视为通过，这里显式把 null 当未勾选，避免出现「不传字段就绕过同意」。 */
  public boolean termsChecked() {
    return Boolean.TRUE.equals(agreeTerms);
  }

  public boolean privacyChecked() {
    return Boolean.TRUE.equals(agreePrivacy);
  }

  public boolean sensitiveChecked() {
    return Boolean.TRUE.equals(agreeSensitive);
  }
}
