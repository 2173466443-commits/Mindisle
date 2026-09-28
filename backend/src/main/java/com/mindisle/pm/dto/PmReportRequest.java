package com.mindisle.pm.dto;

/**
 * 举报一条私信的入参（任务 T5.5 · 需求 FR6.6「私信是唯一允许进入人审的私密内容」）。
 *
 * <p>不加校验注解：与 {@code ReportRequest}、{@code CreatePostRequest} 同一口径，
 * 上限走配置、错误要成一句能直接念给用户的话。</p>
 *
 * @param messageId  被举报的私信 id（必须是举报人<b>自己参与</b>的那条会话里的行）
 * @param reason     理由分类，六选一，取值集合与 {@code ReportService#REASONS} 逐字一致
 *                   （spam / abuse / sexual / privacy / self-harm / other）
 * @param description 补充描述，可空；上限走 {@code mindisle.pm.report-remark-max}
 */
public record PmReportRequest(Long messageId, String reason, String description) {
}
