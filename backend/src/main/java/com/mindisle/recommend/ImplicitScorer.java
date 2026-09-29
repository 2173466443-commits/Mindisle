package com.mindisle.recommend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 隐式评分器（任务 T7.1 · 手册 §10.2 7.1 · 需求 §8.2.1）。
 *
 * <p>协同过滤的输入不是「点没点」，而是 R(u,i)：把同一个人对同一篇帖子的多种行为
 * （浏览、看完、点赞、收藏、评论、举报、不感兴趣）加权累加，再按时间衰减。
 * <b>权重本身不在这里定义</b>——它在 {@code UserActionCatalog.ACTION_WEIGHTS}，
 * 因为埋点写入与算法读取必须是同一张表，否则「收藏 = 5」这件事会出现两种说法。</p>
 *
 * <p>本类只做三件纯计算：时间衰减、夹取下限 0 上限 10、把原始分压成 0–1。
 * 三件事都有可复现的公式，所以单测直接对着数字算，不需要任何替身。</p>
 */
public final class ImplicitScorer {

  private ImplicitScorer() {
  }

  /**
   * 单条行为的贡献分：{@code weight · 0.5^(daysAgo / 半衰期)}。
   *
   * <p>负权重（不感兴趣 −3、举报 −5）乘上衰减后仍然是负的，所以「很久以前点过一次不感兴趣」
   * 的影响会自己退场，不需要额外做过期清理。</p>
   */
  public static double contribution(BigDecimal weight, long daysAgo) {
    if (weight == null) {
      return 0d;
    }
    return weight.doubleValue() * decay(daysAgo);
  }

  /** 衰减因子：当天 1.0，一个半衰期 0.5，未来时间（时钟回拨）按 0 天算，不许出现 >1。 */
  public static double decay(long daysAgo) {
    long days = Math.max(0L, daysAgo);
    return Math.pow(0.5d, (double) days / RecConstants.DECAY_HALFLIFE_DAYS);
  }

  /** 原始分夹到 [0,10]：负反馈可以把一条内容压到 0，但不能压成负数——下游还要跟质量分相加。 */
  public static double clamp(double raw) {
    return Math.min(RecConstants.SCORE_MAX, Math.max(RecConstants.SCORE_MIN, raw));
  }

  /** 把夹好的 0–10 压成 0–1：{@code s / (s + 1)}。s=0→0，s=1→0.5，s=10→0.909，天然单调且不需要归一化系数。 */
  public static double normalize(double clampedScore) {
    double s = clamp(clampedScore);
    return s / (s + 1d);
  }

  /** 新鲜度因子（打分融合里的 w3 项）：按天衰减，半衰期 {@link RecConstants#FRESH_HALFLIFE_DAYS}。 */
  public static double freshness(long ageDays) {
    return Math.pow(0.5d, (double) Math.max(0L, ageDays) / RecConstants.FRESH_HALFLIFE_DAYS);
  }

  /** 按日期差（不是时刻差）算「几天前」：埋点在同一天的多次动作不该因为差几小时而被当成两天。 */
  public static long daysBetween(LocalDate from, LocalDate to) {
    return ChronoUnit.DAYS.between(from, to);
  }

  /** 同上，接收时间戳（{@code user_action.created_at} 直接可用）。 */
  public static long daysBetween(LocalDateTime from, LocalDateTime to) {
    return daysBetween(from.toLocalDate(), to.toLocalDate());
  }
}