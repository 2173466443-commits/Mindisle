package com.mindisle.admin.dto;

import lombok.Data;

/**
 * 「某个标签/类别出现多少次」的通用聚合行（大屏情绪分布饼图、词云、审核结论分布共用）。
 *
 * <p>与 {@link StatusCountRow} 刻意分成两个类而不是做一个泛型壳：
 * 泛型化在这里只会把「这一列在库里叫什么」这件事从类名里藏起来。</p>
 */
@Data
public class LabelCountRow {

  /** 分组键原值：情绪标签（joy/anxiety/…）、话题名、命中词…… */
  private String label;

  private Long cnt;
}