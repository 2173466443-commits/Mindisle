package com.mindisle.pm.dto;

import java.util.List;

/**
 * 会话列表的一页（任务 T5.6 的 U9）。
 *
 * @param list        本页会话，按最后一条消息倒序
 * @param nextCursor  上一页最后一条会话的 lastMsgId，作为 {@code beforeId} 传回来；
 *                    <b>用消息 id 而不是会话 id</b>：本表没有「会话」这张表，
 *                    游标只能落在唯一的全序键上（这是 DDL 把 id 同时定义为排序游标的原话）
 * @param hasMore     还有更多
 * @param unreadTotal 未读总数，页面右上角那个角标；与 {@code GET /api/pm/unread} 同源同算法
 */
public record PmConversationPage(List<PmConversationItem> list, Long nextCursor, boolean hasMore,
                                 int unreadTotal) {
}
