package com.mindisle.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/**
 * 大屏「日 × 24 小时」二维热力的一格（任务 U16-④ · 手册 §9.3 图表 6 的二维版）。
 *
 * <p>为什么要把原来的一维 hour 热力升级成二维：一维那张把窗口里<b>每一天的凌晨两点</b>
 * 压进同一格，于是「工作日深夜塌陷、周末白天回升」和「整周都很平稳」画出来是同一张图。
 * 心理健康运营要的恰恰是这种按星期几错开的节律（考前周、周末孤独高峰），一维格看不见。</p>
 *
 * <p>{@code day} 这里<b>必须</b>是 {@code LocalDate} 而不是像 {@link EmotionDailyRow} 那样出成
 * {@code %m-%d} 展示串：二维网格要按日期对齐到行，前端拿到展示串就得再反解析一次，
 * 跨年时 '%m-%d' 会把 2025-12-31 和 2026-12-31 认成同一天。展示格式由前端一处定死。
 * {@code hour} 同 {@link HourValenceRow}，是 0–23 的整数；缺行的格子由前端补 0，
 * 后端不返回 days×24 的空壳行——「那天那小时没人记录」和「有人记录且效价为 0」是两件事。</p>
 */
@Data
public class DayHourValenceRow {

  /** 记录日期，取自生成列 record_date。 */
  private LocalDate day;

  /** 0–23，取自 HOUR(created_at)。 */
  private Integer hour;

  /** 该日该小时的记录条数。 */
  private Long cnt;

  /** 该日该小时的效价均值，区间 -1..1（色标：负=深、正=浅、0=中性）。 */
  private BigDecimal avgValence;
}
