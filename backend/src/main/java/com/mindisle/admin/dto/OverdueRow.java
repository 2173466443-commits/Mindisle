package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 「超时未办结的工单有几条、最老的那条超了多久」（A2 大屏红色横幅的唯一数据源）。
 *
 * <p>为什么不用 {@code StatusCountRow} 加一个字段：那个类的 {@code status} 是「状态原值」，
 * 把「超时分钟数」塞进一个叫 status 的字段里，读代码的人会先信字段名、再信注释，
 * 于是第一次出错就在这里（阶段 3 那个「两处评论数不一致」的本质也是同一个字段承担两种语义）。
 * 两个数来自同一条 SQL 这一点是对的——分开查会得到两个时刻的读数，
 * 横幅上就会出现「3 条超时」配「最老一条超时 0 分钟」。</p>
 */
@Data
public class OverdueRow {

  /** 超时未办结条数。 */
  private Long cnt;

  /** 其中最老一条已超时的分钟数；没有超时行时为 0。 */
  private Long worstMinutes;
}