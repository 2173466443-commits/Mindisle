package com.mindisle.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 调用流水 ai_call_log（sql/02_ai.sql 表 3 · 需求 §7.2 #24、FR2.7 成本熔断的数据源）。
 *
 * <p><b>这张表没有 deleted 列，所以它不是逻辑删除实体</b>：流水一旦被「软删」就等于
 * 成本账本少了行，而它存在的唯一理由就是记账。全局预算判定（NFR12）读的是
 * {@code SUM(cost_cent)}，任何删除都会让它系统性偏低。</p>
 *
 * <p><b>失败也要写一行</b>（{@code success=0} + {@code error}）：熔断器的「连续失败计数」
 * 和论文里的可用性数字都来自这里，只记成功日志的账本没法证明降级发生过。
 * {@code error} 只写异常类名与消息摘要，<b>严禁写入 API Key、完整提示词或用户原文</b>
 * ——这张表是管理端可读的（T6.2），写进去就等于抄送给后台。</p>
 *
 * <p>{@code firstTokenMs} 就是 N2 的 TTFT。它是「逐字流式是否真的成立」唯一可信的度量：
 * 一次 3 秒完成的 {@code call()} 和一次首字 300ms、总 3s 的 {@code stream()} 在
 * {@code latencyMs} 上完全一样，只有这一列能把它们分开。</p>
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 可空：离线批算与定时任务（周报生成）没有归属用户。 */
  private Long userId;

  /** chat | emotion | risk | audit | summary | report | embed，预算按场景统计（FR2.7）。 */
  private String scene;

  private String model;

  private String promptVersion;

  private Integer tokensIn;

  private Integer tokensOut;

  /** 成本，单位分：预算以整数分计，避免浮点累加误差（DDL 注释原文）。 */
  private Integer costCent;

  private Integer firstTokenMs;

  private Integer latencyMs;

  /** 1 成功 / 0 失败。降级（走离线话术库）也是 0，并在 error 里写 DEGRADED。 */
  private Integer success;

  private String error;

  /** 与日志 MDC traceId 同值，用来把一条流水钉到一次 HTTP 请求上。 */
  private String traceId;

  private LocalDateTime createdAt;
}
