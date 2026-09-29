package com.mindisle.recommend.dto;

import lombok.Data;

/**
 * 一帖一带话题名（{@code RecommendMapper#listApprovedPostTags}），内容相似度的向量来源。
 *
 * <p>取的是<b>话题名</b>而不是话题 id：内容相似度走的是 {@code ItemCf.contentCosine(Set,Set)}，
 * 它的输入是「一串可比的特征」。用 id 也能算（同一套集合运算），但话题名可以直接进
 * {@code ReasonBuilder} 的理由文案（「因为你关注了 #失眠#」），少一次回表。
 * 代价是同名话题必须唯一 —— {@code uk_topic_name} 正是为此存在。</p>
 *
 * <p>只取已过审话题：预审中的话题名不该出现在任何推荐理由里（与话题墙、搜索同一口径）。</p>
 */
@Data
public class PostTagRow {

  private Long postId;

  private String topicName;
}