package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 大屏「24 小时情绪热力」的一格（手册 §9.3 图表 6 · 答辩故事点「凌晨 1–3 点低谷」）。
 *
 * <p>{@code hour} 是 0–23 的整数而不是字符串：热力图要按小时排位，
 * 字符串 '0' 与 '13' 的排序会把中午排到早上前面。缺行的小时由前端补 0，
 * 后端不返回 24 行空壳，因为「没有记录」和「记录为 0 条」在这张表上是两件事。</p>
 */
@Data
public class HourValenceRow {

  /** 0–23，取自 {@code HOUR(created_at)}。 */
  private Integer hour;

  /** 该小时的记录条数。 */
  private Long cnt;

  /** 该小时的效价均值，区间 -1..1（色标按负=深、正=浅，0 是中性）。 */
  private BigDecimal avgValence;
}