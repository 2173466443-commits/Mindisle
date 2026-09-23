package com.mindisle.notify.dto;

import java.util.List;

/**
 * 通知列表的一页（任务 T3.11-b · 需求 FR9.2）。
 *
 * <p>与帖子列表同一套游标口径（{@code beforeId} + {@code nextCursor} + {@code hasMore}），
 * 不给 page/total：通知是「只增不改」的流水，页码分页在有新通知插进来时必然跳页重复。
 * {@code unreadCount} 每一页都带回来，是为了让前端的红点<b>只有一个来源</b>——
 * 单独再开一个 unread 接口就会出现「列表说三条未读、红点显示五条」这种自相矛盾的界面。</p>
 *
 * @param list        本页通知，按 id 倒序（新的在前）
 * @param nextCursor  下一次请求的 beforeId；空列表时为 null
 * @param hasMore     是否还有更旧的未读/已读通知
 * @param unreadCount 当前未读总数（红点数字），与 {@link #list} 同一次查询内一致
 */
public record NotifyPage(List<NotifyItem> list, Long nextCursor, boolean hasMore, long unreadCount) {
}
