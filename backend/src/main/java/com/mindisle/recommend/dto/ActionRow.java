package com.mindisle.recommend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 离线评分器读的一行行为（{@code RecommendMapper#listPostActions}）。
 *
 * <p>别名全部 snake_case，靠 {@code map-underscore-to-camel-case} 映射进本类，
 * 与 {@code DashboardMapper} 同一口径 —— 这个项目里 MyBatis 结果类型一律是
 * {@code @Data} 类而不是 record，别在推荐这里发明第二种。</p>
 *
 * <p><b>为什么带 action_type 与 weight 两列</b>：weight 是打分输入（口径唯一来源是
 * {@code UserActionCatalog.ACTION_WEIGHTS}），action_type 则用来把「负反馈」单独挑出来
 * 做融合公式里的 {@code − w6·负反馈} 与「同类降权」。只带 weight 不带 action_type，
 * 离线侧就没法区分「−3 的不感兴趣」和「−5 的举报」，而这两者在需求 FR5.7 里的处置不同。</p>
 */
@Data
public class ActionRow {

  private Long userId;

  /** {@code user_action.target_id}，本查询已过滤 target_type='post'，所以它就是帖子 id。 */
  private Long postId;

  private String actionType;

  private BigDecimal weight;

  /** 创新点②唯一数据源：动作发生时的心情效价 −5..+5，NULL = 当日无打卡。 */
  private Integer moodValence;

  /** 时间衰减的基准（首次成立时刻，upsert 不刷新它）。 */
  private LocalDateTime createdAt;
}