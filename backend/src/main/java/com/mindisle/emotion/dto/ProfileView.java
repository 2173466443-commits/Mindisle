package com.mindisle.emotion.dto;

import java.util.List;

/**
 * 情绪档案页的一次性响应（任务 T4.10 · 需求 FR3.4 · BR12）。
 *
 * <p><b>四张图一个接口</b>：趋势折线、分布饼、触发词云、日历热力要的是同一个时间窗内的
 * 同一批行。分四个接口会得到四个「今天是几号、什么算可信」互不相同的答案，
 * 而它们在同一个屏幕上并排显示——这正是阶段 3 那个「两处评论数不一致」bug 的形状
 * （见手册 §6.4 第 4 条）。所以这里一次算完，四个投影共享 {@code rows}。</p>
 *
 * <p>{@code accumulating} 是 BR12 的执行位：可信天数 &lt; 3 时为 true，
 * 前端据此画「数据积累中」而不是一条误导性的折线。判定放在服务端，
 * 因为「几天才算多」是产品口径，不该由前端各写一份。</p>
 *
 * @param rangeDays     回看天数：7 / 30 / 90
 * @param fromDate      窗口起点（含）
 * @param toDate        窗口终点（含）
 * @param accumulating  true = 有记录的天数不足 3 天，趋势线不可信（BR12）
 * @param dayCount      窗口内有情绪记录的天数
 * @param recordCount   窗口内记录条数（含不可信）
 * @param confidentCount可信条数（confidence ≥ 阈值）
 * @param uncertainCount不可信条数（FR3.2：计数但不进趋势）
 * @param trend         趋势折线：一天一个点
 * @param distribution  分布饼：按可信条数占比
 * @param wordcloud     触发词云：Top 词面与出现次数
 * @param calendar      日历热力：一天一格，无记录也在（否则热力格会「缺日期」）
 * @param streak        连续记录天数
 * @param sources       来源拆分：checkin / chat / post 各多少条
 */
public record ProfileView(int rangeDays, String fromDate, String toDate, boolean accumulating,
                          int dayCount, int recordCount, int confidentCount, int uncertainCount,
                          List<TrendPoint> trend, List<LabelCount> distribution,
                          List<WordItem> wordcloud, List<CalendarCell> calendar,
                          Streak streak, List<LabelCount> sources) {

  /** 趋势线上的一个点。 */
  public record TrendPoint(String date, String label, String labelZh, Double avgIntensity,
                           int valence, int recordCount, int confidentCount, int uncertainCount) {
  }

  /** 一个键的计数与占比（分布饼与来源拆分共用）。 */
  public record LabelCount(String key, String zh, int count, double ratio) {
  }

  /** 词云的一项。 */
  public record WordItem(String word, int count) {
  }

  /** 日历热力的一格。 */
  public record CalendarCell(String date, boolean hasRecord, boolean checkedIn, int count,
                             String label, String labelZh, int valence, Integer maxIntensity) {
  }

  /** 连续打卡激励：当前连续与历史最长（在窗口内可算的部分）。 */
  public record Streak(int current, int longest, int checkinDays, int totalCheckins) {
  }
}
