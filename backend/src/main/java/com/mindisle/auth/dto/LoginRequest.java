package com.mindisle.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录入参（需求 FR1.2）。图形验证码 + IP/账号双维度失败计数构成第二道防撞库闸门。 */
public record LoginRequest(

    @NotBlank(message = "请填写用户名")
    @Size(max = 32)
    String username,

    @NotBlank(message = "请填写密码")
    @Size(max = 64)
    String password,

    @NotBlank(message = "请填写验证码")
    String captchaId,

    @NotBlank(message = "请填写验证码")
    @Size(max = 8)
    String captchaCode
) {}
