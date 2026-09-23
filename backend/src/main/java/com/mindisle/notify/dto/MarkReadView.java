package com.mindisle.notify.dto;

/**
 * 标记已读的回执（任务 T3.11-b · 需求 FR9.2）。
 *
 * <p>{@code updated} 只数「真的从未读变成已读」的行（SQL 里带 {@code AND is_read = 0}），
 * 所以重复调用回 0 而不是报错——连点两下「全部已读」不是错误。
 * {@code unreadCount} 是改完之后的真相，前端据此直接刷红点，不必再请求一次列表。</p>
 *
 * @param updated     本次变成已读的行数
 * @param unreadCount 标记之后还剩多少条未读
 * @param all         本次是不是「全部已读」
 */
public record MarkReadView(int updated, long unreadCount, boolean all) {
}
