package com.mindisle.post.dto;

/**
 * 提交举报的回执（任务 T3.11 · 需求 FR4.7、FR4.4）。
 *
 * <p><b>恒返 HTTP 200</b>，与发帖、评论同一口径（判据见 {@link PostView} 类注释）：
 * 「你已经举报过了」和「这条内容被多人举报、已经先转入人工审核」都是这次请求<b>成功拿到的答案</b>，
 * 不是失败。前端要的是「按钮变成什么态、下一句说什么」，而这些全在字段里。</p>
 *
 * @param postId              被举报的帖子
 * @param reason              归一化之后的理由码（本次实际生效的那一个）
 * @param reasonLabel         理由中文名，前端回执直接显示，不再自己维护一份映射
 * @param duplicated          true 表示这个人对这条内容早就举报过，本次没有新增记录
 * @param reportCnt           本次之后的举报<b>人数</b>，与 {@code post.report_cnt} 同源
 * @param autoReviewThreshold 转人审阈值，回执里带上它，界面可以说「再 X 人就转人工」
 * @param escalated           本次是否把帖子推成了 HUMAN_REVIEW（并发被别人改走时为 false）
 * @param auditTaskId         这条内容当前那张待审工单的 id（FR4.7「举报即刻生成 audit_task」的物证）
 * @param hotline             非空即「前端必须显示求助卡片」：只在理由为 self-harm 时给（手册 §6.5 第 6 条）
 * @param tip                 给用户看的一句话，可为 null
 */
public record ReportView(Long postId, String reason, String reasonLabel, Boolean duplicated,
        Long reportCnt, Integer autoReviewThreshold, Boolean escalated, Long auditTaskId,
        String hotline, String tip) {
}
