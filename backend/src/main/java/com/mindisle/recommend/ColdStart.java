package com.mindisle.recommend;

/**
 * 冷启动（任务 T7.5 · 手册 §10.2 7.5 · 需求 FR5.5）。
 *
 * <p>判据只有一个数字：这个用户在窗口期内的行为数。够 {@link RecConstants#CF_MIN_INTERACTIONS}
 * 次才交给协同过滤，否则用「注册时选的 3 个话题」做标签召回 + 质量分热度兜底。</p>
 *
 * <p>阈值不是玄学：需求 FR5.5 写的是「交互 ≥20 次后切 CF」，而 20 次以下时 UserCF 的邻居表
 * 几乎一定为空（共同帖数下限直接把它筛光），此时走 CF 的表現是「信息流整屏空白」而不是「推荐不准」——
 * 那是最糟的失败形态，用户会以为社区没人发帖。</p>
 */
public final class ColdStart {

  /** 六路召回的通道名，必须与 {@code recommend_result.recall_channel} 的 ENUM 逐字一致。 */
  public static final String CHANNEL_USERCF = "usercf";
  public static final String CHANNEL_ITEMCF = "itemcf";
  public static final String CHANNEL_CONTENT = "content";
  public static final String CHANNEL_HOT = "hot";
  public static final String CHANNEL_EXPLORE = "explore";
  public static final String CHANNEL_EMOTION = "emotion";

  public static final java.util.Set<String> CHANNELS = java.util.Set.of(CHANNEL_USERCF,
      CHANNEL_ITEMCF, CHANNEL_CONTENT, CHANNEL_HOT, CHANNEL_EXPLORE, CHANNEL_EMOTION);

  private ColdStart() {
  }

  public static boolean cfEligible(long interactionCnt) {
    return interactionCnt >= RecConstants.CF_MIN_INTERACTIONS;
  }

  /** 冷启动且没有话题信号（没勾过兴趣话题）时，只剩热度一条路可走。 */
  public static String firstChannel(long interactionCnt, boolean hasTagSignal) {
    if (cfEligible(interactionCnt)) {
      return CHANNEL_USERCF;
    }
    return hasTagSignal ? CHANNEL_CONTENT : CHANNEL_HOT;
  }

  /** 该不该给这个用户留探索位：冷启动期先别乱撒，容易推出让 ta 不安心的内容。 */
  public static boolean allowExplore(long interactionCnt) {
    return cfEligible(interactionCnt);
  }

  /** 一屏 size 条里应该有几个探索位（1/5，向下取整）。 */
  public static int exploreSlots(int size) {
    return size / RecConstants.EXPLORE_EVERY;
  }

  /** ENUM 校验：写错一个字母 MySQL 会静默存成默认值，通道统计就永久失真。 */
  public static boolean isKnownChannel(String channel) {
    // 显式判 null：CHANNELS 是 Set.of(...)，contains(null) 会抛 NPE。
    // 调用方在离线写库的循环里，一条脏通道值抛出的 NPE 会被 RecommendJob 吞成
    // 「整批没写进去」，表现恰恰是缓存永远读空——比跳过这一条糟得多。
    return channel != null && CHANNELS.contains(channel);
  }
}