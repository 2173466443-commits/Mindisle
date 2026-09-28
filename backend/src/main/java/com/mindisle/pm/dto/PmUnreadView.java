package com.mindisle.pm.dto;

import java.util.List;
import java.util.Map;

/**
 * 未读汇总（任务 T5.4 · 需求 FR6.2，同时是 T5.8 降级轮询的返回体）。
 *
 * <p>{@code byPeer} 是 {@code peerId -> 条数}，只包含<b>有未读</b>的那些人；
 * 前端把它并进会话列表时，列表里未命中的行按 0 处理。为什么不补全 0：
 * 一个 500 人的站不该让「你有 3 条未读」这个响应带 500 个键。</p>
 *
 * @param total 未读总数（角标）
 * @param byPeer 按发件人分组的未读数
 * @param peers  带展示名的发件人摘要，仅 {@code GET /api/pm/unread} 填充；
 *               WS 降级轮询用同一个接口，所以这里也带得上
 */
public record PmUnreadView(int total, Map<Long, Integer> byPeer, List<PmConversationItem> peers) {

  /** 只有总数与分组、不带人名的那个形状（单测与内部调用常用）。 */
  public static PmUnreadView of(int total, Map<Long, Integer> byPeer) {
    return new PmUnreadView(total, byPeer, List.of());
  }
}
