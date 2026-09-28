package com.mindisle.pm.dto;

/**
 * 拉黑／解除拉黑的回执（任务 T5.6 前置 · 需求 FR6.7）。
 *
 * <p>{@code blocked} 是<b>操作之后</b>的事实状态而不是请求里的那个动作：
 * 重复拉黑、并发拉黑、拿同一个 clientToken 重试两次，回执都如实说「现在处于拉黑中」，
 * 前端按钮就不会和后端分叉。形状与 {@code FollowView} 刻意保持一致（action/changed/最终态）。</p>
 *
 * @param targetId 被拉黑的人
 * @param action   block / unblock
 * @param changed  本次有没有真的改变状态（false 表示本来就是这样，一次写入都没做）
 * @param blocked  现在到底拉黑没拉黑
 */
public record PmBlockView(Long targetId, String action, boolean changed, boolean blocked) {
}
