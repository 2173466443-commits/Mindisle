package com.mindisle.recommend;

/**
 * 推荐链路的全部可调参数（手册 §10.1 §10.2 · 需求 §6.2 与 §8.2.1 · 任务 T7.1–T7.8）。
 *
 * <p><b>为什么单独一个常量类</b>：需求 §8.2.4 要求六组对照 + 消融（混合 vs 混合−情绪项）。
 * 消融的做法是「把某个权重置零再跑一遍」，如果权重散落在各 Service 的魔法数字里，
 * 实验组就不可能复现。集中在这里，改一个数 = 换一组实验。</p>
 *
 * <p>列宽约束记在这里以免越界：{@code recommend_result.reason} 是 VARCHAR(200)、
 * {@code score} 是 DECIMAL(8,6)（0–1）、{@code recall_channel} 是六值 ENUM。</p>
 */
public final class RecConstants {

  private RecConstants() {
  }

  // ------------------------------------------------------------- 隐式评分（T7.1）
  /** 需求 §8.2.1：单帖对单用户的隐式分下限 0、上限 10（负反馈能把分压到 0，但不会压成负的）。 */
  public static final double SCORE_MIN = 0d;
  public static final double SCORE_MAX = 10d;
  /** 只取近 30 天的行为（手册 §10.1 第一行「user_action(近30天)」）。 */
  public static final int ACTION_WINDOW_DAYS = 30;
  /** 时间衰减半衰期：14 天前的行为权重折半，防止「上个月看过」永久霸占召回。 */
  public static final int DECAY_HALFLIFE_DAYS = 14;

  // ------------------------------------------------------------- 相似度与融合（T7.2 T7.3）
  /** {@code sim_final = β·sim_cf + (1−β)·sim_content}，β 默认 0.7（手册 T7.3）。 */
  public static final double BETA_CF = 0.7d;
  /** Top-K 邻居数，对应 sys_config {@code rec.topk_neighbor}（手册 T7.2 防 O(n²)）。 */
  public static final int TOP_K_NEIGHBOR = 200;
  /** UserCF 每用户保留的邻居人数（与上面那个 K 不同维度：一个是帖子的邻居，一个是人的邻居）。 */
  public static final int TOP_K_USER_NEIGHBOR = 50;
  /** 共现用户数下限：只有 1 个共同用户的相似对是噪声，直接不进邻居表。 */
  public static final int MIN_CO_USERS = 2;
  /** UserCF 热门惩罚 {@code sim / (1 + ln|I(i)| · α)} 的 α（手册 T7.2）。 */
  public static final double HOT_PENALTY_ALPHA = 0.3d;
  /** 邻居表里单条邻居的最小相似度：低于它写进 JSON 也只是占位，不如不写。 */
  public static final double MIN_NEIGHBOR_SCORE = 0.01d;

  // ------------------------------------------------------------- 打分融合（手册 §10.1）
  /** w1·CF + w2·质量分 + w3·新鲜度 + w4·情绪匹配 − w5·重复 − w6·负反馈。 */
  public static final double W_CF = 0.45d;
  public static final double W_QUALITY = 0.20d;
  public static final double W_FRESH = 0.15d;
  public static final double W_EMOTION = 0.15d;
  public static final double W_REPEAT = 0.25d;
  public static final double W_NEGATIVE = 0.30d;
  /** 新鲜度半衰期 7 天：社区内容贬值快，一周前的帖不能再当「新」发。 */
  public static final double FRESH_HALFLIFE_DAYS = 7d;

  // ------------------------------------------------------------- 冷启动与重排（T7.5 T7.6）
  /** 交互数低于它就走标签/热度召回，够 20 次才交给 CF（需求 FR5.5，阈值可配）。 */
  public static final int CF_MIN_INTERACTIONS = 20;
  /** 同一话题在信息流里连续出现不得超过 2 条（手册 §10.2 7.6 打散）。 */
  public static final int TOPIC_SPREAD_CAP = 2;
  /** 7 天内曝光过的不再重复推荐（手册 §10.2 7.6，配合 user_action(expose) 的 day_bucket）。 */
  public static final int EXPOSE_DEDUP_DAYS = 7;
  /** 每 5 个位置留 1 个探索位（1/5），保证覆盖率不至于只推热门。 */
  public static final int EXPLORE_EVERY = 5;

  // ------------------------------------------------------------- 情绪匹配（T7.4 创新点②）
  /** {@code user_action.mood_valence} 的取值域 −5..+5，归一化到 −1..+1 时除以它。 */
  public static final int VALENCE_RANGE = 5;

  // ------------------------------------------------------------- 在线出参与缓存（T7.10 T7.16）
  public static final int FEED_DEFAULT_SIZE = 20;
  public static final int FEED_MAX_SIZE = 50;
  public static final int SIMILAR_DEFAULT_SIZE = 6;
  public static final int SIMILAR_MAX_SIZE = 12;
  /** 预计算结果的有效期：超过就退回热度榜兜底，同时提示离线任务该重算了。 */
  public static final long RESULT_TTL_MINUTES = 90L;
  /** 离线阶段每用户最多带多少候选进入重排（再多就是给 CPU 加班，在线根本不展示）。 */
  public static final int CANDIDATE_POOL_CAP = 300;

  // ------------------------------------------------------------- 规模保护（手册 §10.2 7.10）
  /**
   * 物品规模阈值：窗口内<b>不同帖子数</b>超过它，本轮就只算 ItemCF、跳过 UserCF。
   *
   * <p>两条通道的代价不是一个量纲。ItemCF 的 O(物品²) 只发生在离线写
   * {@code item_similarity} 那一步，而且被 Top-K=200 与每用户向量截到
   * {@link ItemCf#USER_VECTOR_CAP} 篇封住了上界；UserCF 恰好相反 —— 它没有可以预先落盘的
   * 「物品邻居表」，每一个用户都要拿他的向量去和<b>全体</b>用户向量算一次余弦，
   * 一轮下来的代价是 O(用户² × 向量长度)，涨的是人数而不只是帖数。</p>
   *
   * <p>所以「物品多到算不动」的真正含义是「这一轮别再逐用户扫全表」。阈值对应的键是
   * {@code sys_config.rec.scale_item_cap}，读不到就用本常量；把它调小不是作弊，
   * 这条判据本来就是给人按现场数据量决定的（论文「可扩展性讨论」要能回答「调到多少会降级」）。
   * 置 0 或负数 = 关掉这道保护，恒按两条通道都算。</p>
   */
  public static final int ITEM_SCALE_CAP = 5000;
  /** {@code recommend_result.reason} 列宽。 */
  public static final int REASON_MAX = 200;
}