package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 危机预警工单 alert_ticket（需求 §7.2 #19、FR10.5 · 创新点 3「L0-L3—12356 转介闭环」的落地载体）。
 *
 * <p>{@code level} 只有 L2/L3 两个取值：需求 §5.2 明确「L0/L1 只软引导不建单」，
 * 所以建不建单这件事在 ENUM 上就被钉死，不靠代码自觉。</p>
 *
 * <p>{@code evidenceText} 是<b>脱敏后</b>的片段（FR10.5 要求 200 字上下文）：工单必须独立于原内容存活
 * ——发帖人可以随时删帖，辅导员不能因此失去依据。同时它也是全站唯一会持久化「用户危机表述」的
 * 管理端可见字段之一，因此隐私类命中（手机号、微信号）必须在写入前被替换掉（NFR8 数据最小化）。</p>
 */
@Data
@TableName("alert_ticket")
public class AlertTicket {

  /**
   * 工单六态（与 sql/07_audit.sql 的 ENUM 逐字一致，<b>全小写</b>）。
   *
   * <p>这是本表最容易写错的地方：{@code audit_task.status} 是全大写
   * （PENDING/PROCESSING/...），而 {@code alert_ticket.status} 是全小写
   * （pending/claimed/...）。阶段 3 的建单代码用字面量躲过去了，阶段 6 的六态状态机
   * 若还各处写字符串，MySQL 的 ENUM 会把写错的那一条<b>静默降成第一个枚举值</b>
   * （非严格模式下不报错），于是「已闭环」变成「待认领」。所以常量只在这里定义一次。</p>
   */
  public static final String STATUS_PENDING = "pending";
  public static final String STATUS_CLAIMED = "claimed";
  public static final String STATUS_DOING = "doing";
  public static final String STATUS_CLOSED = "closed";
  public static final String STATUS_FALSE_POSITIVE = "false_positive";
  public static final String STATUS_EXPIRED = "expired";

  /** 未办结的三态（SLA 超时告警只数这三态，已闭环的不算积压）。 */
  public static final java.util.List<String> OPEN_STATUSES =
      java.util.List.of(STATUS_PENDING, STATUS_CLAIMED, STATUS_DOING);

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** L2 / L3，见类注释。 */
  private String level;

  /** 被救助对象，逻辑外键 user.id。 */
  private Long userId;

  /** chat / post / hole / pm，触发来源（发帖链路用 post 与 hole）。 */
  private String sourceType;

  /** 来源逻辑主键，如 post.id。 */
  private Long sourceId;

  /** 脱敏证据片段，最长 500 列宽，业务侧只送 200 字上下文。 */
  private String evidenceText;

  /** 触发时的风险分 0.000-1.000。 */
  private BigDecimal riskScore;

  /** 命中的风险词，逗号分隔，最长 200。 */
  private String triggerWords;

  /** pending / claimed / doing / closed / false_positive / expired 六态。 */
  private String status;

  private Long assigneeId;

  private LocalDateTime claimAt;

  private LocalDateTime closeAt;

  /** 处置时限：L3=+30min、L2=+4h（需求 §5.2）。 */
  private LocalDateTime slaAt;

  private String handleNote;

  private LocalDateTime followupAt;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
