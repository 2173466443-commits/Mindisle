package com.mindisle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 隐私授权入参（需求 FR1.10「我的隐私」· §12 规范 3「同意留痕」）。
 *
 * <p>用 @Pattern 而不是枚举，是为了让非法值在进入 service 前就变成 400 + 明确文案，
 * 同时与 sql/01_account.sql 里 user_consent.consent_type 的 ENUM 取值逐字对齐：
 * 数据库能存的值与接口能收的值必须是同一份清单，否则会出现「接口收了但落库失败」的 500。
 */
public record ConsentRequest(

    @NotBlank(message = "请指明授权事项")
    @Pattern(regexp = "^(TERMS|PRIVACY|SENSITIVE_INFO|EMOTION_SHARE|CRISIS_CONTACT)$",
             message = "授权事项不在允许范围内")
    String consentType,

    @NotBlank(message = "请指明是授予还是撤回")
    @Pattern(regexp = "^(GRANT|WITHDRAW)$", message = "操作只允许 GRANT 或 WITHDRAW")
    String action,

    /** 当时的协议/文案版本号，举证关键；留空时服务端按 sys_config 的 prompt.version 兜底。 */
    @Size(max = 16)
    String contentVersion,

    /** 触发本次授权的页面或弹窗标识，便于事后复盘「用户是在哪一步点的同意」。 */
    @Size(max = 64)
    String sourcePage
) {}
