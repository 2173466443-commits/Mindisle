package com.mindisle.recommend;

/**
 * 可解释推荐理由（任务 T7.7 · 手册 §10.2 7.7 · 需求 FR5.8）。
 *
 * <p>答辩里最能说明「算法真的在跑」的不是分数，而是卡片上那句「为什么推给我」。
 * 所以理由必须<b>由通道与真实信号生成</b>，不能是一句通用套话——通用文案在六路召回里
 * 长得一模一样，等于把埋点丢掉的解释力又丢了。</p>
 *
 * <p>写库前按码点截到 {@link RecConstants#REASON_MAX}（列宽 VARCHAR(200)）：
 * 理由里会拼用户自选的话题名，话题名是 VARCHAR(32) 但可能全是中文，
 * 按 UTF-16 单元截会把代理对劈成半个字符，MySQL 直接报编码错，整批预计算跟着回滚。</p>
 */
public final class ReasonBuilder {

  /**
   * 什么可解释信号都拿不到时，唯一允许出现的一句话。
   *
   * <p><b>它只许发给 hot 与未知通道</b>。2026-09-29 前这句写在 {@code switch} 之前，
   * 于是「一条只被一个人读过的 itemcf 帖」也会顶着这句上场 —— 那是替热度榜撒谎，
   * 而且当场违反手册 §11.6 对 FR5.9 的判据「理由与 {@code recall_channel} 一致」。
   * Gate7 在真接口上量到的第一个产品级 bug，就是这个早退。</p>
   */
  static final String NO_SIGNAL_HOT = "社区里最近被很多屿民读完的内容";

  private ReasonBuilder() {
  }

  /**
   * 生成一句理由。
   *
   * @param channel     六路召回之一，见 {@link ColdStart#CHANNELS}
   * @param topicName   该帖命中话题名（可空）
   * @param emotionName 该帖的情绪标签（可空，走 emotion 通道时才有意义）
   * @param coReaderCnt 相似帖的共同阅读人数（itemcf 通道用，&lt;=0 表示拿不到）
   */
  public static String of(String channel, String topicName, String emotionName, int coReaderCnt) {
    String topic = safe(topicName);
    String emotion = safe(emotionName);
    // 判据写在每个分支里，不再共用一个「两个信号都缺」的总闸（那正是 Gate7 量到的早退 bug）：
    // 每路召回只许说它手上那份信号，缺哪份就收起哪半句，缺得一份不剩才让给热度兜底句。
    switch (channel == null ? "" : channel) {
      case ColdStart.CHANNEL_ITEMCF:
        if (coReaderCnt > 0) {
          return "看过这篇的屿民也看过 #" + topicOrFallback(topic) + "（" + coReaderCnt + " 人共鸣）";
        }
        // 离线批次固定传 0（共同阅读人数不落缓存），所以线上走的就是这一支。
        return topic == null ? "和你读过的内容很像" : "和你读过的内容很像：" + topic;
      case ColdStart.CHANNEL_USERCF:
        // 这一路的句子主干是「口味相近的屿民」，话题名只是补充，所以缺名字时用占位标签
        // 「#屿民推荐」不算编造事实（该形态钉在 ReasonBuilderTest#missingSignalsDegradeHonestly）。
        return topic == null && emotion == null ? "和你口味相近的屿民正在看"
            : "和你口味相近的屿民正在看 #" + topicOrFallback(topic);
      case ColdStart.CHANNEL_CONTENT:
        return topic == null ? "因为你关注的话题里有它" : "因为你关注了 #" + topic;
      case ColdStart.CHANNEL_EMOTION:
        // 这条通道的真实信号在「人」这一侧（当前 valence 偏低），不在「帖」这一侧：
        // 话题与标签都拿不到时，只说得住用户那半边事实，不替帖子编一个「被很多人标记为被治愈」。
        return topic == null && emotion == null
            ? "今天你的心情偏低落，这条想安静陪你一会儿"
            : "今天你的心情偏低落，这条被很多人标记为「" + (emotion == null ? "被治愈" : emotion) + "」";
      case ColdStart.CHANNEL_EXPLORE:
        return topic == null ? "换个口味：一篇你还没读过的帖子"
            : "换个口味：#" + topic + " 里一篇还没被读过的帖子";
      case ColdStart.CHANNEL_HOT:
        return topic == null ? NO_SIGNAL_HOT : "社区今天讨论最多的 #" + topic;
      default:
        // 未知通道的「社区推荐：」不能带话题名以外的承诺，话题缺失时同样让给热度兜底句。
        return topic == null ? NO_SIGNAL_HOT : "社区推荐：" + topic;
    }
  }

  private static String topicOrFallback(String topic) {
    return topic == null ? "屿民推荐" : topic;
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 按码点截断（不是按 char），并保证截完仍是合法字符串。 */
  public static String cut(String value, int maxCodePoints) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.codePointCount(0, value.length()) <= maxCodePoints) {
      return value;
    }
    int end = value.offsetByCodePoints(0, maxCodePoints);
    // offsetByCodePoints 只会落在码点边界上，绝不会截出半个代理对；再兜一次保险。
    if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end);
  }

  /** 落库前的统一出口：所有 reason 都必须走这里，避免某条通道忘了截断。 */
  public static String forStore(String channel, String topicName, String emotionName,
      int coReaderCnt) {
    return cut(of(channel, topicName, emotionName, coReaderCnt), RecConstants.REASON_MAX);
  }
}