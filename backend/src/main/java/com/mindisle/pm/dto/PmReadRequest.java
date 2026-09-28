package com.mindisle.pm.dto;

/**
 * 已读上报的入参（{@code /app/read} 与 {@code POST /api/pm/read} 共用）。
 *
 * <p>{@code upToId} 是「我看到了这一条为止」的单调游标，而不是逐条 id 列表：
 * 一次进页面可能同时压着 30 条未读，逐条上报要么 30 次写、要么一个百元素数组，
 * 而「读到了哪一条」这件事天然就是一个游标。上限与口径同
 * {@link com.mindisle.notify.dto.MarkReadRequest} 的 ids 那条相反——
 * 通知表没有可用的游标语义（它的幂等靠文案），私信表有 id 全序，所以这里用游标。</p>
 *
 * @param peerId 会话另一端（必填：不给就等于「把我全部私信标已读」，那是前端一个漏传字段
 *               就能造成的不可逆状态变更，与通知那边「两个字段都不给报 400」同一个理由）
 * @param upToId 已读到的消息 id；null 表示这个会话里全部
 */
public record PmReadRequest(Long peerId, Long upToId) {
}
