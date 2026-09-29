package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 「某个时间窗内的工单总数与已闭环数」（A2 大屏「今日闭环率」）。
 *
 * <p>闭环率的分子含 {@code false_positive}：误报被判定并记录，同样是这条工单走完了流程。
 * 只把 {@code closed} 算作闭环会让误报率高的时段看起来「审核效率很低」，
 * 而它实际表达的是「机审在这个时段更不准」——那是 §10 的风险评估指标，不该混进运营指标。</p>
 */
@Data
public class ClosedRow {

  /** 窗口内新建的工单总数。 */
  private Long cnt;

  /** 其中已闭环（closed + false_positive）的条数。 */
  private Long closedCnt;
}