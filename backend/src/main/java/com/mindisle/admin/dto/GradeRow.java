package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 大屏「年级聚合柱状图」的一行（手册 §9.3 图表 5 · 需求 FR3.6）。
 *
 * <p><b>这一行是 FR3.6 的合规边界</b>：只有 {@code grade} 与聚合计数两个字段，
 * 没有任何个体明细、没有任何可反推到人的组合键。加字段之前要先把这条写在类注释里的
 * 约束挪到需求文档去讨论，而不是「先加个 uid 方便排查」。</p>
 */
@Data
public class GradeRow {

  /** FRESH / SOPH / JUNIOR / SENIOR / OTHER（DDL 的 ENUM）。 */
  private String grade;

  /** 该年级的人数（已过滤软删账号）。 */
  private Long cnt;
}