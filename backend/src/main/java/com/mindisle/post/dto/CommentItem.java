package com.mindisle.post.dto;

import java.time.LocalDateTime;

/**
 * 一条评论（任务 3.7 · 手册 §6.2 U4 评论区）。
 *
 * <p><b>匿名不回 authorId</b>：与 {@link PostListItem} 同一口径（需求 FR1.4）。
 * 只要 userId 出现在响应体里，前端「不显示」是拦不住抓包的，所以从源头就不发。</p>
 *
 * @param id            评论 id
 * @param postId        所属帖子 id
 * @param parentId      被回复的评论 id，一级评论为 null
 * @param rootId        所属一级评论 id，一级评论为 null
 * @param content       评论内容（匿名评论里的联系方式已在落库前遮罩）
 * @param authorId      作者 id，匿名评论恒为 null
 * @param authorName    展示名：实名走昵称，匿名走马甲名，作者已注销走兜底文案
 * @param anonymous     是否匿名发表
 * @param replyToName   「回复 @某某」里的被回复者展示名；一级评论为 null
 * @param likeCnt       评论点赞数（真相在 post_like，评论点赞链路尚未开放，当前恒为库值）
 * @param status        PENDING / PUBLISHED / REJECTED / DELETED
 * @param auditTip      非空即「这条只有你自己看得到，还在审核」，只给作者本人
 * @param createdAt     发表时刻
 * @param authorIsPostOwner 评论者是否就是本帖作者（BR4：自评不进推荐质量分，T3.10 埋点要靠它跳过）
 */
public record CommentItem(
        Long id,
        Long postId,
        Long parentId,
        Long rootId,
        String content,
        Long authorId,
        String authorName,
        boolean anonymous,
        String replyToName,
        int likeCnt,
        String status,
        String auditTip,
        LocalDateTime createdAt,
        boolean authorIsPostOwner) {
}
