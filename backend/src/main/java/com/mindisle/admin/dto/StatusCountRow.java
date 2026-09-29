package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 「某一个状态有多少条」的通用聚合行（阶段 6 三处复用：审核队列、危机工单、举报）。
 *
 * <p>之所以不叫 {@code AuditStatusCount}：同一形状的 {@code GROUP BY status} 在阶段 6 至少要写三次，
 * 各写一个类会得到三个字段名不同的同一种东西（{@code cnt} vs {@code count} vs {@code total}），
 * 前端就要做三次适配。用 @Data 而非 record 的理由见 {@code EmotionGroupRow} 的类注释。</p>
 */
@Data
public class StatusCountRow {

  /** 状态原值：PENDING/PROCESSING/... 或 pending/claimed/...（工单侧 DDL 是全小写，不在这里统一）。 */
  private String status;

  private Long cnt;
}