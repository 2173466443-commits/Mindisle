package com.mindisle.recommend.dto;

import lombok.Data;

/**
 * 一帖的评论正向反应计数（{@code RecommendMapper#listCommentReactions}）。
 *
 * <p>两列的分工照 {@code EmotionBoost.comfortFromReactions(positiveCnt, totalCnt)}：
 * 分母是「这条帖子底下有多少条已发布评论」，分子是「其中情绪标签为 joy/trust 的条数」。
 * total = 0 时那个函数返回 null，语义是「这篇还没被验证过能不能安抚人」，
 * 于是情绪通道对它整体沉默 —— 而不是给它 0 效价（0 会被当成「中性」，
 * 反而让一个刚发出来、没人回的低落帖拿到最高的情绪匹配分）。</p>
 *
 * <p><b>为什么用评论的情绪标签而不是「抱抱」这种关键词</b>：阶段 4 已经给每条评论跑过
 * 词典 + LLM 级联识别并把 {@code emotion_primary} 落了库，再养一套「治愈词表」就是
 * 第二个真值来源。词典换版本时只要重算这张表，推荐侧不需要改代码。</p>
 */
@Data
public class ComfortRow {

  private Long postId;

  /** joy / trust 的评论条数。 */
  private Integer positiveCnt;

  /** 已发布评论总条数（含楼中楼，不含待审与已删）。 */
  private Integer totalCnt;
}