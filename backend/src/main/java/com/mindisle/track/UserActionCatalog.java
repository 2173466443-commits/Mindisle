package com.mindisle.track;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 行为埋点的口径常量表（任务 T3.10 · 需求 FR5.1 + §8.2.1 · 手册 §6.1 行 3.10）。
 *
 * <p><b>这张表为什么必须存在，而不是让每个调用点自己写数字</b>：{@code user_action.weight} 是
 * 协同过滤唯一的打分输入（需求 §8.2.1 的 {@code R(u,i) = Σ w}）。同一种行为在不同入口
 * 记了不同的分，模型学到的就不是「用户喜欢什么」而是「哪段代码写的埋点」，
 * 而这种错在离线指标上完全看不出来——指标只会整体变差，没人能定位到「广场埋了 3 分、
 * 详情页埋了 1 分」。所以本类是全站<b>唯一</b>一份权重表，
 * {@link UserActionRecorder} 之外的代码不许自己填 weight。</p>
 *
 * <p><b>取值的唯一来源是需求 FR5.1 与 §8.2.1 那行公式</b>，两处口径一致：
 * 曝光 0.1、停留≥3s 计 1、点赞 3、评论 4、收藏 5、完读 2、举报 −5、不感兴趣 −3、关注 +5。
 * {@code sql/05_recommend.sql} 的列注释写着「浏览1/点赞3/收藏5/看完4/不喜欢-5」，
 * 与原文冲突（把「评论 4」错抄成「看完 4」、把「举报 −5」错抄成「不喜欢 −5」）。
 * 需求正文是上游真值，建表注释只是它的一句摘要，故本表按原文取数，差异已记进
 * {@link com.mindisle.entity.UserAction} 类注释与 dev-log。</p>
 *
 * <p><b>这里存的是「单条行为的原始权重」，不是评分</b>：需求 §8.2.1 那句
 * 「下限截断为 0，上限 10」作用在 {@code R(u,i)} 这个<b>求和结果</b>上，属阶段 7 的
 * {@code ImplicitScorer}。若在埋点层就截断，一次举报会把 −5 写成 0，
 * 负反馈信号就此消失，而事后从这张表里读不出「本来是被截断过」——
 * 落库永远存原始值，截断只发生在读侧。</p>
 */
public final class UserActionCatalog {

  private UserActionCatalog() {
  }

  // ------------------------------------------------------------- target_type

  /** 与 {@code user_action.target_type} 的 ENUM 逐字一致（小写）。 */
  public static final String TARGET_POST = "post";
  public static final String TARGET_COMMENT = "comment";
  public static final String TARGET_TOPIC = "topic";
  public static final String TARGET_USER = "user";

  /**
   * 一条 AI 回复（任务 T4.19）。
   *
   * <p>需求 §7.2 #9 原本只列了前四种，因为这个枚举当时只考虑「内容行为」；手册 T4.19 要求
   * 把赞踩并入 {@code user_action}，对象是「一条消息」，四种里一种都不是。补一个枚举值
   * 而不是硬塞成 {@code post}：帖子 id 和消息 id 都是各自表的自增主键，
   * 都从 1 开始，(post, 17) 一旦被用来表示「第 17 条消息」，阶段 7 做用户-物品矩阵时
   * 这两条会静默合并成同一个物品。另起一张表也不对——手册明写「并入 {@code user_action}」。
   * 加值由 {@code sql/15_stage4_backfill.sql} 第 1 条改动落地。
   * 约定：{@code target_type=message} 时 {@code target_id} 与 {@code message_id} 同值，
   * 冗余那一列只为省一次 JOIN（需求 §8.4 的对话质量评估集要直接和 chat_message 联表）。</p>
   */
  public static final String TARGET_MESSAGE = "message";

  public static final Set<String> TARGET_TYPES =
      Set.of(TARGET_POST, TARGET_COMMENT, TARGET_TOPIC, TARGET_USER, TARGET_MESSAGE);

  // ------------------------------------------------------------- action_type

  /** 十种行为，取值与 {@code user_action.action_type} 的 ENUM 逐字一致（小写）。 */
  public static final String ACTION_VIEW = "view";
  public static final String ACTION_LIKE = "like";
  public static final String ACTION_COLLECT = "collect";
  public static final String ACTION_COMMENT = "comment";
  public static final String ACTION_READ_THROUGH = "read_through";
  public static final String ACTION_DISLIKE = "dislike";
  public static final String ACTION_FOLLOW = "follow";
  public static final String ACTION_REPORT = "report";
  public static final String ACTION_EXPOSE = "expose";
  public static final String ACTION_AI_FEEDBACK = "ai_feedback";

  public static final Set<String> ACTION_TYPES = Set.of(ACTION_VIEW, ACTION_LIKE, ACTION_COLLECT,
      ACTION_COMMENT, ACTION_READ_THROUGH, ACTION_DISLIKE, ACTION_FOLLOW, ACTION_REPORT,
      ACTION_EXPOSE, ACTION_AI_FEEDBACK);

  /**
   * 行为 → 权重（需求 FR5.1 + §8.2.1）。
   *
   * <p>{@code ai_feedback} 不在 FR5.1 的清单里——FR5.1 讲的是<b>内容</b>隐式反馈，
   * 而这条的载体是 AI 消息（手册 T4.19）。它既然落在同一张表、同一个 weight 列上，
   * 就必须有一个数，否则 NOT NULL 的 weight 只能填 1.00 那个默认值，等于「和浏览同权重」——
   * 一个没有任何依据的数。本项目的口径是 <b>UP +2 / DOWN −2 / NONE 0</b>：
   * ① 它是<b>显式</b>表态，按 FR5.1 的量纲排在「完读 2」这一档；② 它对推荐的价值是
   * 「这条回复被不接受」这一负向信号，取 −2 与 +2 对称，不给「踩」额外加权——
   * 用户对 AI 的不满有大量来自模型风格而非内容，加权过狠会让一次幻觉毁掉整个用户画像；
   * ③ NONE（取消反馈）必须是 0 而不是删行，行留着才看得出「这个人表过态又撤回了」。
   * 这是<b>决策</b>不是漏做，已回写进手册 §7.5 的 T4.19 行与 dev-log。</p>
   */
  public static final Map<String, BigDecimal> ACTION_WEIGHTS = Map.of(
      ACTION_EXPOSE, new BigDecimal("0.10"),
      ACTION_VIEW, new BigDecimal("1.00"),
      ACTION_READ_THROUGH, new BigDecimal("2.00"),
      ACTION_LIKE, new BigDecimal("3.00"),
      ACTION_COMMENT, new BigDecimal("4.00"),
      ACTION_COLLECT, new BigDecimal("5.00"),
      ACTION_FOLLOW, new BigDecimal("5.00"),
      ACTION_DISLIKE, new BigDecimal("-3.00"),
      ACTION_REPORT, new BigDecimal("-5.00"),
      ACTION_AI_FEEDBACK, BigDecimal.ZERO);

  /** 赞踩三态各自的分（{@link #ACTION_WEIGHTS} 里 ai_feedback 记 0，真实分由这个方法给）。 */
  public static final BigDecimal AI_FEEDBACK_UP = new BigDecimal("2.00");
  public static final BigDecimal AI_FEEDBACK_DOWN = new BigDecimal("-2.00");
  public static final BigDecimal AI_FEEDBACK_NONE = BigDecimal.ZERO;

  /** 反馈值 → 分。大小写与前后空格不敏感；认不出来的值按 NONE 计 0 分（不抛异常：埋点不该让接口 500）。 */
  public static BigDecimal aiFeedbackWeight(String feedback) {
    String value = feedback == null ? "" : feedback.trim().toUpperCase(java.util.Locale.ROOT);
    return switch (value) {
      case "UP" -> AI_FEEDBACK_UP;
      case "DOWN" -> AI_FEEDBACK_DOWN;
      default -> AI_FEEDBACK_NONE;
    };
  }

  // ------------------------------------------------------------- scene

  /**
   * 来源场景（需求 §7.2 #9 的 scene 列 · 手册 T7.16 用它区分「曝光」与「主动浏览」）。
   *
   * <p>列宽 VARCHAR(16)，故这里全是不超过 16 字符的短词。</p>
   */
  public static final String SCENE_FEED = "feed";
  public static final String SCENE_PLAZA = "plaza";
  public static final String SCENE_FOLLOWING = "following";
  public static final String SCENE_SEARCH = "search";
  public static final String SCENE_TOPIC = "topic";
  public static final String SCENE_DETAIL = "detail";
  public static final String SCENE_AI = "ai";

  public static final Set<String> SCENES = Set.of(SCENE_FEED, SCENE_PLAZA, SCENE_FOLLOWING,
      SCENE_SEARCH, SCENE_TOPIC, SCENE_DETAIL, SCENE_AI);

  /** 认不出的场景一律降级成 feed 之外最中性的值，而不是抛异常：埋点丢一个标签比丢一行数据好。 */
  public static String normalizeScene(String scene) {
    if (scene == null) {
      return null;
    }
    String value = scene.trim().toLowerCase(java.util.Locale.ROOT);
    return SCENES.contains(value) ? value : SCENE_FEED;
  }

  // ------------------------------------------------------------- 采集开关

  /**
   * 哪些行为要顺带采一份「当时的心情」。
   *
   * <p>需求 §8.2.2 的情绪感知项 {@code emotion_match(u,i)} 要的是「用户此刻的效价」，
   * 而 {@code mood_valence} 每一行都得查一次 {@code emotion_record}。曝光一次请求就是二十行，
   * 全站最热的写路径不该为了一个用不上的字段多二十次查询，所以曝光<b>不</b>采；
   * 举报与不感兴趣是负向信号，需求把情绪项只写在「低落时优先推治愈内容」这一条上，
   * 对负反馈不做情绪假设，故也不采。剩下这六种是正向内容信号，是创新点②真正的输入。</p>
   */
  public static final Set<String> MOOD_TRACKED = Set.of(ACTION_VIEW, ACTION_LIKE, ACTION_COLLECT,
      ACTION_COMMENT, ACTION_READ_THROUGH, ACTION_FOLLOW, ACTION_AI_FEEDBACK);

  public static boolean tracksMood(String actionType) {
    return actionType != null && MOOD_TRACKED.contains(actionType);
  }

  /** 权重查表；未知行为返回 null，由调用方决定是「不记」而不是「记成 1 分」。 */
  public static BigDecimal weightOf(String actionType) {
    return actionType == null ? null : ACTION_WEIGHTS.get(actionType);
  }

  // ------------------------------------------------------------- 曝光采样

  /**
   * 曝光采样分母：FR5.1「曝光（可选采样 30%）」里的 30%。
   *
   * <p>取 10 分之 3 而不是 100 分之 30，是因为判据用取模；两者比例相同，
   * 但取模 10 让「任意连续 10 个条目里恰好 3 个被记」成为可以口算验证的性质，
   * 答辩时被问「你这个采样是不是随机数种子影响结果」能当场给结论。</p>
   */
  public static final int EXPOSE_SAMPLE_MODULO = 10;
  public static final int EXPOSE_SAMPLE_HITS = 3;

  /**
   * 一条曝光该不该记（手册 T7.16 的「曝光去重」与 FR5.1 的「采样 30%」在这里合并成一次判定）。
   *
   * <p><b>为什么不掷骰子</b>：{@code Random} 的结果取决于进程内外的调用次数，
   * 同一个用户对同一条内容的采样结论在两次运行之间会变，
   * 而阶段 7 的对照实验（FR5.10 的 rec_mode）要求「同一份输入重跑得到同一份样本」，
   * 否则 F1/点击率的差异里混着采样噪声。取模把随机性挪到 id 空间上：
   * 每个用户恒定命中 3/10 的条目、同一对 (用户, 条目) 永远同结论、跨重启可复现。
   * 乘 31 是为了让相邻用户错开命中位——若直接用 {@code (userId + itemId) % 10}，
   * 同一页里的所有用户会命中<b>完全相同</b>的那三条内容，热榜会自我强化。</p>
   */
  public static boolean sampleExpose(long userId, long itemId) {
    return Math.floorMod(userId * 31L + itemId, EXPOSE_SAMPLE_MODULO) < EXPOSE_SAMPLE_HITS;
  }

  // ------------------------------------------------------------- 停留阈值

  /**
   * 停留计时阈值（FR5.1「停留时长 ≥3s 计 1 分」）。
   *
   * <p>写常量而不是写配置：这条数是需求正文里的口径，改它等于改评分函数，
   * 属阶段 7 的实验变量，不该由运维在 {@code sys_config} 里顺手调。
   * 真要做灵敏度实验，改这里并重跑 {@code UserActionRecorderTest}。</p>
   */
  public static final int VIEW_MIN_DURATION_MS = 3000;

  /** 够不够记一条 view。null 与负数一律不算（前端漏传不是「停留 0 毫秒」，是「没量到」）。 */
  public static boolean isEnoughDwell(Integer durationMs) {
    return durationMs != null && durationMs >= VIEW_MIN_DURATION_MS;
  }
}
