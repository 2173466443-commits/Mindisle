package com.mindisle.pm.dto;

import java.util.List;

/**
 * 在线状态（任务 T5.4 · 需求 FR6.3）。
 *
 * <p>两个载荷是同一条判断的两种问法：{@code online} 回答「此刻这几个人在不在线」，
 * 页面进会话时查一次；{@code changed} 是服务端<b>主动推</b>的那一条，只带状态翻转的那个人。
 * 之所以两种都要：只有轮询就没有 2 秒内的实时性（T5.8 判据），
 * 只有推送就没有「我刚打开页面，上一次推送在断线期间丢了」的那个起点。</p>
 *
 * @param online 请求里在线的那几个 id（不在线的<b>不出现</b>，与 byPeer 同一口径）
 * @param userId 状态翻转的那个人（推送专用；查询时为 null）
 * @param isOnline 他现在在不在线（推送专用）
 *
 * <p><b>本形状里没有「谁在线」的名单</b>：全站可见的广播走 {@code /topic/presence}，
 * 载荷只有 {@code {onlineCount, ts}} 两个键（见 {@code PresenceRegistry}）。
 * 需求 FR6.5 原文写的是 {@code /topic/online}，照字面实现等于把「某个人在不在线」
 * 发给全站每一个连接——那是一处新的隐私扩散，而没有任何一条需求要它。</p>
 */
public record PmPresenceView(List<Long> online, Long userId, Boolean isOnline) {

  /** 查询用的形状。 */
  public static PmPresenceView ofOnline(List<Long> online) {
    return new PmPresenceView(online, null, null);
  }

  /** 推送用的形状。Jackson 会把 {@code isOnline} 序列化成 {@code online}，前端读 online 即可。 */
  public static PmPresenceView ofChange(long userId, boolean online) {
    return new PmPresenceView(List.of(), userId, online);
  }
}
