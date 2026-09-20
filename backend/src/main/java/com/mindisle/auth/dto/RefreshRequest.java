package com.mindisle.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌入参（需求 FR1.2 会话续期）。refreshToken 只在登录/刷新响应里出现过一次。 */
public record RefreshRequest(@NotBlank(message = "缺少 refreshToken") String refreshToken) {}
