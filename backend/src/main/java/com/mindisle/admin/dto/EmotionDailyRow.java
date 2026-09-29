package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 大屏「近 14 日活跃与情绪指数」双轴折线的一行（手册 §9.3 图表 2）。
 *
 * <p><b>纵轴口径</b>：{@code avg_valence} 落在 -1..1（真库现量 MIN=-1 / MAX=1 / AVG=-0.404），
 * 折线的第二条轴必须按这个区间给刻度，照「1-5 分制」画会把整条曲线压到轴外。</p>
 *
 * <p>{@code day} 直接用 {@code DATE_FORMAT(record_date,'%m-%d')} 出成展示串，
 * 而不是传 {@code LocalDate} 再让前端格式化：这个轴标签在 ECharts 里要参与
 * 「两条series对齐同一刻度」，两边各格式化一次就会出现一条是 09-21 一条是 9-21 的错位。
 * 展示口径由 SQL 一处定死。</p>
 */
@Data
public class EmotionDailyRow {

  /** 日期标签，形如 09-29。 */
  private String day;

  /** 当日有情绪记录的去重用户数（活跃口径）。 */
  private Long activeCnt;

  /** 当日效价均值，<b>区间 -1..1</b>（emotion_record.valence 是归一效价，不是 5 分制） */
  private BigDecimal avgValence;

  /** 当日记录条数（图表 tooltip 用，与「多少人」区分开）。 */
  private Long recordCnt;
}