package com.mindisle.recommend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 候选帖的元数据（{@code RecommendMapper#listRecommendablePosts}）。
 *
 * <p>离线打分需要知道的只有这几列，所以这里<b>不选 content</b>：
 * 一次全表扫描把正文带进 Java 堆，500 帖还能撑住、5 万帖就是 OOM，
 * 而正文对打分的唯一贡献已经在 {@code quality_score} 与 {@code emotion_primary} 里被压缩过了。</p>
 *
 * <p>{@code riskLevel} 必须带：手册 §10.6 第 2 条明写「L2/L3 危机帖不进相似位」，
 * 这条判据在离线与在线两处都要用，缺了它就只能靠理由文案把危机帖混进去。</p>
 */
@Data
public class PostMetaRow {

  private Long id;

  private Long userId;

  /** normal / hole / help（树洞帖有到期销毁，新鲜度项要单独看它）。 */
  private String type;

  private LocalDateTime publishedAt;

  /** 由 {@code RecommendMapper#backfillQualityScores} 回填，重算之前库里恒为 0。 */
  private BigDecimal qualityScore;

  /** 作者发布那一刻的主导情绪标签，七类之一。 */
  private String emotionPrimary;

  /** L0 / L1 / L2 / L3，危机分级双通道取高的结果。 */
  private String riskLevel;

  /** 是否匿名：理由文案与卡片展示名都靠它，不能在推荐侧另判一次。 */
  private Integer isAnonymous;
}