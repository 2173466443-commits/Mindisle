package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审核留痕 audit_record（需求 §7.2 #18 · FR7.7 三通道各留一行 · sql/07_audit.sql 表 26 · 任务 T6.1 首次写入）。
 *
 * <p><b>它是「只增不改」的流水，不是状态表</b>：一条任务被认领、被通过、被驳回，
 * {@code audit_task} 上只有最后一次的结果，而「谁在什么时候依据什么下的结论」全在这里。
 * 所以本类没有任何 update 语义（Mapper 也只有 insert 与 select），
 * {@code updated_at} 是 DDL 自带的形式一致，业务上不会变。</p>
 *
 * <p><b>为什么机审也要写一行</b>：论文要复现「DFA 与 LLM 的通道一致率」，
 * 需求 FR7.7 要求三通道各自留痕（dfa / llm / image）。机审行 {@code operatorId} 为空，
 * 人审行 {@code operatorId} 必填——这两件事由 {@code AdminAuditService} 保证，
 * 不靠调用方自觉。</p>
 *
 * <p><b>{@code wordlibVersion} 与 {@code modelVersion} 是合规线，不是装饰</b>：
 * 手册 §9.1 第 5 步要求「结论可追溯到规则版本」。词库热更新（T6.2）之后，
 * 同一条内容可能得到不同结论，没有版本号的留痕无法解释这件事。</p>
 */
@Data
@TableName("audit_record")
public class AuditRecord {

  /** decision 取值：机审/人审的结论码，与 audit_task.result 同源。 */
  public static final String DECISION_PASS = "PASS";
  public static final String DECISION_REVIEW = "REVIEW";
  public static final String DECISION_BLOCK = "BLOCK";
  public static final String DECISION_TAG = "TAG";
  public static final String DECISION_L2 = "L2";
  public static final String DECISION_L3 = "L3";
  /** 人审专用的两个结论（DDL 的 VARCHAR 放得下，不需要新枚举）。 */
  public static final String DECISION_HUMAN_PASS = "HUMAN_PASS";
  public static final String DECISION_HUMAN_REJECT = "HUMAN_REJECT";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 audit_task.id；机审直出结论时可为空。 */
  private Long taskId;

  /** post / comment / pm / hole / ai_reply / image / topic（topic 档见 sql/17_stage6_alter.sql）。 */
  private String targetType;

  private Long targetId;

  /** dfa / llm / image：本条留痕属于哪一路机审，人审写它当时依据的那一路。 */
  private String channel;

  /** 模型版本，如 deepseek-v4-pro@2026-09；LLM 通道必填。 */
  private String modelVersion;

  /** 词库版本，如 v0.1；DFA 通道必填，热更新时递增（T6.2）。 */
  private String wordlibVersion;

  /** DFA/Trie 引擎版本，用于复现实验（手册 §5.1 v1.1.2 补列）。 */
  private String engineVersion;

  /** 本次命中词条，逗号分隔，上限 500 字符（超长由服务侧裁，不让整条留痕丢掉）。 */
  private String hitWords;

  /** 机审原始输出 JSON / LLM 原始回复，排查与回归用。 */
  private String rawOutput;

  /** 本通道耗时毫秒（FR7.6 机审 P95 达标分析）。 */
  private Integer latencyMs;

  /** PASS / REVIEW / BLOCK / TAG / L2 / L3 / HUMAN_PASS / HUMAN_REJECT。 */
  private String decision;

  private BigDecimal riskScore;

  /** 结论文本理由，人审时写的是管理员填的那句（FR4.5 要回执给作者）。 */
  private String reason;

  /** 人审操作人，逻辑外键 user.id；机审为空。 */
  private Long operatorId;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}