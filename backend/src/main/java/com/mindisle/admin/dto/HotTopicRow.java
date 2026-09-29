package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 大屏「高频话题词云」的一行（手册 §9.3 图表 4 · 复用 echarts-wordcloud）。
 *
 * <p>只取 {@code audit_status='APPROVED'} 的话题：词云是公开可见的展示面，
 * 把一个还在预审里的话题名推到大屏上，等于替它做了一次曝光。</p>
 */
@Data
public class HotTopicRow {

  private Long id;

  /** 话题名，词云的展示文本。 */
  private String name;

  /** 帖数，词云字号的权重来源之一。 */
  private Long postCnt;

  /** 热度分（定时重算，需求 FR4.6）。 */
  private BigDecimal hotScore;
}