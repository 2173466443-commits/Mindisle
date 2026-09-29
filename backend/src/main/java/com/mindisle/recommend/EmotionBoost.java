package com.mindisle.recommend;

/**
 * 情绪感知加权（任务 T7.4 · 创新点② · 手册 §10.2 7.4 · 需求 FR5.3 FR5.9）。
 *
 * <p>公式照手册写：{@code emotion_match(u,i) = 1 − |valence_now(u) − comfort_valence(i)|}，
 * 两侧都从 −5..+5 归一化到 −1..+1，所以差值最大为 2，结果夹到 0..1。</p>
 *
 * <p><b>为什么只在 {@code mood(u) < 0} 时启用</b>：这条加权的目标是给心情低落的人
 * 端上「被很多人标记为被治愈」的内容。对心情本来就好的人启用，会把安抚型内容
 * 推到他们面前，等于用算法制造低沉——这是本项目在伦理自查里明确拒掉的写法。
 * 数据来自 {@code user_action.mood_valence}（发行为动作那一刻采集的心情效价），
 * 帖子侧的 {@code comfort_valence} 来自评论区的正向反应统计（被治愈/抱抱）。</p>
 *
 * <p>两侧任一为 NULL（未采集）时返回 0，即「这项不参与打分」，而不是当成 0 效价：
 * 当成中性会把「没采到数据」的用户误判成心情一般，那批用户正好是最需要照顾的一批。</p>
 */
public final class EmotionBoost {

  private EmotionBoost() {
  }

  /** −5..+5 → −1..+1；null 原样返回 null（「不知道」必须与「0」区分开）。 */
  public static Double normalize(Integer valence) {
    if (valence == null) {
      return null;
    }
    int clamped = Math.max(-RecConstants.VALENCE_RANGE, Math.min(RecConstants.VALENCE_RANGE, valence));
    return (double) clamped / RecConstants.VALENCE_RANGE;
  }

  /** 相似度本体：{@code 1 − |Δ|}，夹到 0..1。任一侧缺失＝0。 */
  public static double match(Integer moodValence, Integer comfortValence) {
    Double mood = normalize(moodValence);
    Double comfort = normalize(comfortValence);
    if (mood == null || comfort == null) {
      return 0d;
    }
    return Math.max(0d, Math.min(1d, 1d - Math.abs(mood - comfort)));
  }

  /**
   * 是否启用：心情效价 &lt; 0 才启用。0 与正数一律返回 0。
   *
   * <p>心情为 0 被划到「不启用」是刻意的：0 是「未采集」与「平静」的公共取值，
   * 拿它当低落来处理会大面积误伤；宁可让这条通道对平静用户沉默。</p>
   */
  public static double boost(Integer moodValence, Integer comfortValence) {
    if (moodValence == null || moodValence >= 0) {
      return 0d;
    }
    return match(moodValence, comfortValence);
  }

  /**
   * 帖子侧的「安抚效价」：由评论区正向反应数与总数之比映射到 −5..+5。
   *
   * <p>正向反应数 &ge; 总数 → +5；一条正向都没有 → −5；没有评论（total=0）→ null，
   * 即「这篇帖子还没被验证过能不能安抚人」，不给它情绪加权。</p>
   */
  public static Integer comfortFromReactions(int positiveCnt, int totalCnt) {
    if (totalCnt <= 0 || positiveCnt < 0) {
      return null;
    }
    double ratio = Math.min(1d, Math.max(0d, (double) positiveCnt / totalCnt));
    return (int) Math.round(ratio * 2 * RecConstants.VALENCE_RANGE - RecConstants.VALENCE_RANGE);
  }

  /** 供融合公式使用：{@code w4 · boost}。 */
  public static double weighted(Integer moodValence, Integer comfortValence) {
    return RecConstants.W_EMOTION * boost(moodValence, comfortValence);
  }
}