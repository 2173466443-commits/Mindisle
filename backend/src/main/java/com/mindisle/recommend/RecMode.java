package com.mindisle.recommend;

/**
 * A/B 分流（任务 T7.8 · 手册 §10.2 7.8 · 需求 FR5.10）。
 *
 * <p>分流因子只用「用户 ID 尾号」这一件事，且<b>必须在离线写缓存和在线读缓存两处用同一个函数</b>：
 * 两处各写一套判断，会出现「缓存按 cf 批次写、在线按 hot 批次读」——结果是永远读空，
 * 页面却看不出报错（因为空批次会自动退回热度兜底）。</p>
 *
 * <p>{@code recommend_result.mode} 的 ENUM 是 {@code cf/hot/ab}：cf 与 hot 是两组在线对照，
 * {@code ab} 留给离线消融批次（不对外读），所以 {@link #forUser} 只可能返回前两个值。</p>
 */
public final class RecMode {

  public static final String MODE_CF = "cf";
  public static final String MODE_HOT = "hot";
  public static final String MODE_AB = "ab";

  /** 尾号 0–6 走 CF（7 成），7–9 走纯热度（3 成）：小样本内测阶段先保证 CF 有足够曝光量。 */
  public static final int CF_TAIL_BOUND = 7;

  private static final int TAIL_MODULO = 10;

  private RecMode() {
  }

  public static String forUser(long userId) {
    int tail = (int) Math.floorMod(userId, TAIL_MODULO);
    return tail < CF_TAIL_BOUND ? MODE_CF : MODE_HOT;
  }

  /** 只有这三条值可以写进 ENUM：任何别的写法都会在 INSERT 时被 MySQL 静默改成默认值。 */
  public static boolean isKnown(String mode) {
    return MODE_CF.equals(mode) || MODE_HOT.equals(mode) || MODE_AB.equals(mode);
  }

  public static boolean isHot(String mode) {
    return MODE_HOT.equals(mode);
  }
}