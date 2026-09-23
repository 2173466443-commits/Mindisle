package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审核任务 audit_task（需求 §7.2 #17、FR7.3 · sql/07_audit.sql 表 25 · 本阶段由任务 T3.11 首次写入）。
 *
 * <p><b>它是「处置队列」，不是「举报登记表」</b>：一条内容同一时刻只允许有一条待办任务
 * （DDL 的 {@code uk_target_pending(target_type,target_id,status)}），第 2 到第 N 个举报人的
 * 落点在 {@link ContentReport}，这里只留「最高优先级」那一份。所以本类的
 * {@code remark} 不参与计数，{@code report_cnt} 也不从这里算——从有唯一键约束的那张表算才是对的。</p>
 *
 * <p><b>{@code channel} 为什么在举报来源下仍然是 dfa</b>：DDL 的三个取值
 * （dfa / llm / image）描述的是「哪一路机审产出了这条结论」，举报本身不是机审通道。
 * 本阶段的做法是<b>提交举报时顺手对目标内容做一次 DFA 复扫</b>，于是 channel=dfa 名副其实、
 * result/risk_score/risk_level 也有真值可填（见 {@code ReportService#rescan}）。
 * 阶段 6 的人审若要用别的通道，改的是这一行的来源标记而不是再编一个枚举值。</p>
 *
 * <p>{@code assigneeId}/{@code remark} 之外的处置字段（PROCESSING/PASSED/...）属任务 6.1，
 * 本阶段只建 PENDING 任务与升优先级，不代管理员下结论。</p>
 */
@Data
@TableName("audit_task")
public class AuditTask {

  /** source 取值：机审转人审 / 主动抽检 / 用户举报（FR4.4 FR7.3）。 */
  public static final String SOURCE_MACHINE = "machine";
  public static final String SOURCE_HUMAN = "human";
  public static final String SOURCE_REPORT = "report";

  public static final String CHANNEL_DFA = "dfa";

  public static final String STATUS_PENDING = "PENDING";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** post / comment / pm / hole / ai_reply / image，与 DDL ENUM 逐字一致。 */
  private String targetType;

  /** 送审对象逻辑主键。 */
  private Long targetId;

  /** machine / human / report。 */
  private String source;

  /** dfa / llm / image，见类注释。 */
  private String channel;

  /** 机审结论原文：PASS / BLOCK / REVIEW / TAG（本阶段来自 DFA 复扫）。 */
  private String result;

  /** 风险分 0.000-1.000，与 alert_ticket.risk_score 同构。 */
  private BigDecimal riskScore;

  /** L0-L3，与 post.risk_level、alert_ticket.level 同源。 */
  private String riskLevel;

  /** 受理管理员，逻辑外键 user.id；举报进入队列时为空。 */
  private Long assigneeId;

  /** PENDING / PROCESSING / PASSED / REJECTED / ESCALATED。 */
  private String status;

  /** 处置时限：L3=+30min、L2=+4h、其余按举报默认时限（需求 §5.2 + 配置 mindisle.report.sla-hours）。 */
  private LocalDateTime slaAt;

  /** 审核备注：本阶段写「理由/举报人/累计次数/复扫结论/词库版本」，最长 500。 */
  private String remark;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
