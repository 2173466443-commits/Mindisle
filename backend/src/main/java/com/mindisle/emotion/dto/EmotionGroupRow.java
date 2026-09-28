package com.mindisle.emotion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * 「某一天 × 某一个标签」的情绪聚合行（任务 T4.9/T4.10 的唯一读模型）。
 *
 * <p><b>为什么一行只到 (record_date, label) 这一层</b>：档案页要的三张图 ——
 * 趋势折线、分布饼、日历热力 —— 需要的其实是同一份分组结果的不同投影。
 * 一条 {@code GROUP BY record_date, label} 就能喂饱三张图；
 * 反过来，为每张图各写一条聚合 SQL，会得到三个「平均强度」互不相同的时刻，
 * 而它们在同一个页面上并排显示（这正是阶段 3 那个「两处评论数不一致」bug 的形状）。</p>
 *
 * <p><b>用 @Data 类而不是 record</b>：MyBatis 往 record 里注入要靠构造器参数名，
 * 而 {@code -parameters} 编译开关在 pom 里没打开，参数名会被编成 arg0/arg1，
 * 结果是「本地能跑、CI 上全是 null」。带 setter 的 POJO 走的是 map-underscore-to-camel-case，
 * 不依赖编译选项。</p>
 *
 * <p>{@code uncertainCnt} 是 {@code confidence < }BR12 阈值 0.6 的条数：需求 FR3.2 要求它
 * <b>计入总记录数但不进趋势线</b>，所以阈值判定放在 SQL 里（阈值由服务层作为参数传入，
 * 不写死在 mapper 里），Java 侧只做投影，不再重复一次比较。</p>
 */
@Data
public class EmotionGroupRow {

  private LocalDate recordDate;

  /** joy/trust/anger/sadness/fear/disgust/neutral 之一。 */
  private String label;

  /** 该天该标签的总条数（含不可信的）。 */
  private Integer cnt;

  /** 可信条数（confidence ≥ 阈值），趋势与分布只用它。 */
  private Integer confidentCnt;

  /** 不可信条数，界面用它解释「为什么今天有点但线没有」。 */
  private Integer uncertainCnt;

  /** 可信记录的平均强度 1.0-5.0；全不可信时为 null，由服务层决定怎么显示。 */
  private BigDecimal avgIntensity;

  /** 来源：checkin / chat / post 各占多少，档案页要能分开（见 EmotionDay 的组装）。 */
  private Integer checkinCnt;

  /** 该天该标签下最短的一段触发文本（词云/触发词回看用，已在库里脱敏截断过）。 */
  private String sampleSnippet;
}
