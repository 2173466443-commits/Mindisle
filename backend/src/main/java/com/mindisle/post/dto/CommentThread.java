package com.mindisle.post.dto;

import java.util.List;

/**
 * 一棵评论子树（任务 3.7 · 需求 FR4.4「一级 + 楼中楼」）。
 *
 * <p>页面上只有两层：{@code root} 是一级评论，{@code replies} 是平铺在它下面的回复。
 * 回复之间再互相引用，也只靠 {@code CommentItem.replyToName} 表达「回复了谁」，
 * 不做第三层缩进——手机屏幕上三层缩进等于把正文挤成一条缝。</p>
 *
 * @param root       一级评论
 * @param replies    本页返回的回复（按 id 正序，最多 {@code CommentService.REPLY_PAGE_SIZE} 条）
 * @param replyTotal 这棵子树下的回复总数，前端据此显示「查看 N 条回复」
 */
public record CommentThread(CommentItem root, List<CommentItem> replies, long replyTotal) {
}
