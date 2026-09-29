package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 推荐结果缓存 recommend_result（任务 T7.6 / T7.9 / T7.10 · 需求 §7.2 #21 · 手册 §10.1 在线侧）。
 *
 * <p><b>这张表是「离线算完、在线只读」的交接点</b>：在线接口 {@code GET /api/feed/recommend}
 * 不在这里跑任何模型，只按 {@code (user_id, scene, mode, position)} 取一屏。
 * 需求 §6.2 的「P95 ≤ 200ms」就是这么省下来的 —— 协同过滤的开销全在 30 分钟一次的离线作业里。</p>
 *
 * <p>{@code score} 列宽是 {@code DECIMAL(8,6)}，语义域 0–1，所以写它之前必须先把
 * 隐式分从 0–10 归一化（{@code ImplicitScorer.normalize}）。把 10 分制的原值写进这一列，
 * MySQL 不会报错，只会静默溢出成 99.999999，而下游「按 score 排序」看起来仍是绿的。</p>
 *
 * <p>{@code recall_channel} 是六路召回的<b>唯一</b>归属记录（需求 §6.4 的六组对照组要按通道出指标），
 * 也是 D7「日志打印各召回通道占比」的数据源。写错一个字母 MySQL 会静默存成 ENUM 默认值，
 * 因此本表的值一律走 {@code ColdStart.CHANNEL_*} 常量，且由
 * {@code ColdStart.isKnownChannel} 在落库前校验。</p>
 *
 * <p>{@code is_exposed} / {@code exposed_at} 是手册 §5.1 v1.1.2 的补列：
 * 曝光的<b>真相</b>在 user_action(expose)（采样后的埋点），这里只是「这一行有没有被发出去过」的
 * 批次内标记，用来让重算之间的信息流不至于每次刷新都一模一样。</p>
 */
@Data
@TableName("recommend_result")
public class RecommendResult {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  private Long userId;

  /** feed / related，与 DDL 的 ENUM 逐字一致（小写）。 */
  private String scene;

  /** 被推荐的帖子 id，逻辑外键 post.id。 */
  private Long itemId;

  /** 综合打分，0–1（融合公式的输出），越靠前越大。 */
  private BigDecimal score;

  /** 六路召回之一，取值见 {@link com.mindisle.recommend.ColdStart#CHANNELS}。 */
  private String recallChannel;

  /** 可解释推荐文案（任务 T7.7），最长 200，由 ReasonBuilder.forStore 统一截断。 */
  private String reason;

  /** 本批次生成模式：cf / hot / ab（需求 §6.4 消融与 A/B 对照靠这一列分组）。 */
  private String mode;

  /** 组内位置，从 0 开始，在线分页直接按它取（手册 §5.1 v1.1.2 补列）。 */
  private Integer position;

  /** 是否已曝光过（本批次内），1 表示已经发给过这个用户。 */
  private Integer isExposed;

  /** 首次曝光时间，配合 rec.expose_dedup_days 做 N 天内不重复推荐。 */
  private LocalDateTime exposedAt;

  /** 本批次计算时间，超过 TTL 在线侧退回热度兜底。 */
  private LocalDateTime calcAt;

  /** 逻辑删除：用户点「不感兴趣」时只置这一位，不物理删，便于重算前对比。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}