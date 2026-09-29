package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * A2 大屏顶部「核心指标卡」的一行数据（手册 §9.3 图表 1 · 需求 FR8.2）。
 *
 * <p><b>这十二个数必须来自同一条 SQL</b>：分十二次查会得到十二个时刻的读数，
 * 大屏上就会出现「今日新增工单 0 条」和「危机数 3 条」并排挂着的自相矛盾——
 * 而这两句话在各自那次查询里都完全正确。一条 {@code SELECT (子查询) , (子查询) ...}
 * 是这条判据唯一的实现方式，代价是一行十二个标量子查询，代价可以接受（都走索引，且 5 秒轮询）。</p>
 *
 * <p>字段名对应 SQL 里的 snake_case 别名，靠 {@code map-underscore-to-camel-case} 映射；
 * 计数列一律装箱成 {@code Long} 而不是 {@code int}：SUM 在零行时返回 NULL，
 * 基本类型会把「今天还没花钱」变成「数据库挂了」。</p>
 */
@Data
public class DashboardStatsRow {

  /** 今日活跃用户数（user_action 当日去重）。 */
  private Long dau;

  /** 今日新注册。 */
  private Long newUserCnt;

  /** 今日 AI 对话轮次（assistant 消息条数，一轮 = 一条回复）。 */
  private Long chatRoundCnt;

  /** 今日发帖数。 */
  private Long postCnt;

  /** 今日危机工单数（L2 + L3）。 */
  private Long crisisCnt;

  /** 今日 AI 费用（分），FR8.2 判据「= ai_call_log 汇总」由 SQL 直接对账。 */
  private Long costCent;

  /** 待办审核任务数（A3 工作台）。 */
  private Long auditPendingCnt;

  /** 未认领危机工单数（A3 工作台）。 */
  private Long ticketPendingCnt;

  /** 待处置举报数（A3 工作台）。 */
  private Long reportPendingCnt;

  /** 已超 SLA 但仍未办结的工单数（A2 红色横幅）。 */
  private Long overdueCnt;

  /** 今日 AI token 消耗（入+出）。费用恒为 0 分时代谢图仍要有能看的数。 */
  private Long tokenCnt;

  /**
   * 今日群体效价均值。<b>区间是 -1..1，不是 1..5</b>：
   * {@code emotion_record.valence} 在 DDL 里是 {@code TINYINT DEFAULT 0}，
   * 情绪引擎输出的是归一效价（真库现量 MIN=-1 / MAX=1 / AVG=-0.404）。
   * 前端画这条线时纵轴要按 [-1,1] 给，照「5 分制」画会把整条曲线压到坐标轴下面。 */
  private BigDecimal avgValence;
}