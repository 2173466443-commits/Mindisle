package com.mindisle.pm.dto;

/**
 * 举报一条私信的回执（任务 T5.5 · 需求 FR6.6「私信是唯一允许进入人审的私密内容」）。
 *
 * <p><b>为什么没有 content_report</b>：那张表 {@code post_id NOT NULL}，且
 * {@code target_type} 的 ENUM 只有 post/comment，装不下一条私信（现查 sql/07_audit.sql）。
 * 而 {@code audit_task.target_type} 的 ENUM 里<b>本来就有 pm</b>，
 * 所以举报私信直接落一条 {@code audit_task(source='report', target_type='pm')}，
 * 不为一行数据去 ALTER 一张别的表。</p>
 *
 * @param taskId  生成的审核任务 id；极端并发下拿不到时为 null（不影响举报本身成立）
 * @param status 任务状态，V1 恒为 PENDING（处置在阶段 6 的 T6.1）
 * @param tip     给用户看的那句话，含「私信会进人工审核」这句二次告知（BR10）
 */
public record PmReportView(Long taskId, String status, String tip) {
}
