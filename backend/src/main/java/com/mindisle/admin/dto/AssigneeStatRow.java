package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 「某一个审核人办了多少、平均多久」的聚合行（手册 §9.2 A4 要求的两个运营指标）。
 *
 * <p>只统计已裁决（PASSED/REJECTED）的行：把 PROCESSING 的行也算进「平均耗时」会得到
 * 一个「越大越慢」的假指标——未办结的那些行 updated_at 就是认领时间，差值是「已经拖了多久」，
 * 不是「办一件要多久」。</p>
 */
@Data
public class AssigneeStatRow {

  /** user.id。 */
  private Long assigneeId;

  /** 办结条数。 */
  private Long cnt;

  /** 平均办结秒数（created_at → updated_at）；库里是整数秒，展示层再换算成分钟。 */
  private Long avgSeconds;

  /** 超时条数：办结时已超过 sla_at（A4 的「超时率」分子）。 */
  private Long overdueCnt;
}