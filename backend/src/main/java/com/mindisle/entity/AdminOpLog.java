package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 管理员特权操作审计 admin_op_log（需求 §7.2 #24 前半 · BR10「特权操作 100% 留痕」·
 * sql/08_config.sql 表 27 · 任务 T6.5/T6.6 首次写入）。
 *
 * <p><b>本表原先有 DDL 没有代码</b>：阶段 3–5 的「解匿」「读私信」「导出」全都做完了，
 * 但一条审计都没落——这不是漏写 SQL，是漏写了一张表的存在。手册 §9.4 把 Gate6 的第 4 条判据
 * 定成「解匿操作的审计覆盖率 = 100%」，靠的就是这张表，所以它必须先于任何 A6/A7/A9 功能存在。</p>
 *
 * <p><b>{@code operatorRole} 是快照不是关联</b>：查的时候按 {@code operator_id} 关联 user 也能拿到角色，
 * 但角色会在事后被改。BR10 要回答的是「当时他有没有权力做这件事」，
 * 所以必须在写入的那一刻把角色抄进本行（DDL 注释里的「角色变更后仍可追责」就是这个意思）。</p>
 *
 * <p><b>{@code result} 三档都要写</b>：只写成功等于没有审计——「谁试图越权」恰恰是
 * 管理端最需要留痕的那一类。{@code DENIED} 由服务侧在鉴权失败时写，{@code FAIL} 由异常分支写。</p>
 */
@Data
@TableName("admin_op_log")
public class AdminOpLog {

  // —— action 动作码（DDL 是 VARCHAR(64)，新增动作不改表结构）——
  /** T6.5 解匿（FR2.5 BR10）：必须填事由。 */
  public static final String ACTION_REVEAL_ANONYMOUS = "REVEAL_ANONYMOUS";
  /** T6.1 审核通过。 */
  public static final String ACTION_AUDIT_PASS = "AUDIT_PASS";
  /** T6.1 审核驳回。 */
  public static final String ACTION_AUDIT_REJECT = "AUDIT_REJECT";
  /** T6.1 审核认领。 */
  public static final String ACTION_AUDIT_CLAIM = "AUDIT_CLAIM";
  /** T6.1-b 危机工单流转。 */
  public static final String ACTION_TICKET_HANDLE = "TICKET_HANDLE";
  /** T6.4 申诉受理。 */
  public static final String ACTION_APPEAL_HANDLE = "APPEAL_HANDLE";
  /** T3.11-b 举报处置。 */
  public static final String ACTION_REPORT_HANDLE = "REPORT_HANDLE";
  /** T6.6 A6 禁言。 */
  public static final String ACTION_MUTE_USER = "MUTE_USER";
  /** T6.6 A6 解除禁言。 */
  public static final String ACTION_UNMUTE_USER = "UNMUTE_USER";
  /** T6.6 A6 封禁。 */
  public static final String ACTION_BAN_USER = "BAN_USER";
  /** T6.6 A6 恢复账号。 */
  public static final String ACTION_RESTORE_USER = "RESTORE_USER";
  /** T6.6 A7 置顶。 */
  public static final String ACTION_POST_TOP = "POST_TOP";
  /** T6.6 A7 加精。 */
  public static final String ACTION_POST_FEATURE = "POST_FEATURE";
  /** T6.6 A7 下架。 */
  public static final String ACTION_POST_TAKEDOWN = "POST_TAKEDOWN";
  /** T6.6 A7 恢复上架。 */
  public static final String ACTION_POST_RESTORE = "POST_RESTORE";
  /** T6.2 词库/参数热更新。 */
  public static final String ACTION_UPDATE_CONFIG = "UPDATE_CONFIG";
  /** T6.6 A9 报表导出（D9）。 */
  public static final String ACTION_EXPORT_CSV = "EXPORT_CSV";
  /** 阶段 5 已实现但欠审计的读私信（BR10 补账）。 */
  public static final String ACTION_READ_PM = "READ_PM";

  /** result 三档。 */
  public static final String RESULT_SUCCESS = "SUCCESS";
  public static final String RESULT_FAIL = "FAIL";
  public static final String RESULT_DENIED = "DENIED";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 操作人，逻辑外键 user.id。 */
  private Long operatorId;

  /** 操作时角色快照：USER / AUDITOR / ADMIN / SUPER / COUNSELOR。 */
  private String operatorRole;

  /** 动作码，取本类常量。 */
  private String action;

  /** 被操作对象描述，如 post:1024 / user:88（BR10 要求可追溯）。 */
  private String target;

  /** 被操作对象主键，全局操作（导出、刷新词库）可为空。 */
  private Long targetId;

  /** 来源 IP，兼容 IPv6 的 45 字符。 */
  private String ip;

  /** 客户端标识，异常行为分析用（写入前截到 255）。 */
  private String userAgent;

  /** 参数快照与事由；解匿必须填（FR2.5）。 */
  private String detail;

  /** SUCCESS / FAIL / DENIED。 */
  private String result;

  /** 本次操作服务端耗时毫秒。 */
  private Integer costMs;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}