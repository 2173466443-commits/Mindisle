package com.mindisle.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.mindisle.recommend.dto.ActionRow;
import com.mindisle.recommend.dto.ComfortRow;
import com.mindisle.recommend.dto.PostMetaRow;
import com.mindisle.recommend.dto.PostTagRow;

/**
 * 推荐链路的聚合读数 Mapper（任务 T7.1 / T7.2 / T7.5 / T7.12 / T7.13 · 手册 §10.2）。
 *
 * <p><b>它不 extends BaseMapper</b>：这张「表」其实不存在。它的方法跨
 * user_action / post / post_topic / topic / comment / post_like / user_profile 七张真相表
 * 取召回与打分需要的输入，没有哪一个实体能代表它。放进 {@code com.mindisle.mapper}
 * 是为了 {@code @MapperScan("com.mindisle.**.mapper")} 扫得到 —— 放 {@code recommend/}
 * 包里它会静默不成 bean，症状是启动时 UnsatisfiedDependency，而不是编译错误。</p>
 *
 * <p>结果类型一律是 {@code recommend/dto} 下的 {@code @Data} 类（不用 record，
 * 与 {@link DashboardMapper} 的 {@code admin/dto/*Row} 同口径）。列名到字段名靠
 * {@code map-underscore-to-camel-case}，所以别名必须写成 snake_case。</p>
 *
 * <p><b>时间窗口参数都从 Java 侧算好传进来</b>，不在 SQL 里写 {@code NOW() - INTERVAL 30 DAY}：
 * 窗口长度是 {@code RecConstants.ACTION_WINDOW_DAYS}，单测要能改它、论文实验要能调它，
 * 藏在 SQL 字符串里就变成「改一处要重跑集成测试的魔法数」。</p>
 */
@Mapper
public interface RecommendMapper {

  /**
   * 时间窗内全部帖子维度的行为（隐式反馈矩阵的原料，需求 §6.2 创新点②）。
   *
   * <p><b>一次全量读，不在循环里按用户查</b>：矩阵是「用户 × 物品」的稀疏表，
   * 按用户查会把 O(用户数) 次往返压进离线作业；而 {@code deleted=0} +
   * {@code created_at >= since} 已把行数限制在 30 天窗口内（手册 §10.2 第 7.1 条）。</p>
   *
   * <p>{@code target_type='post'} 排除了 comment/topic/user/message 维度的行为 ——
   * 协同过滤的「物品」在本项目里只有帖子，混进关注关系会让「我关注了谁」被当成
   * 「我喜欢这类内容」。</p>
   *
   * <p>{@code action_type='expose'} <b>不</b>排除，这是有意的：曝光带 weight=0.1，
   * 是「看过但没兴趣」的负样本证据，{@code UserCf.neighbors} 算共现人数时要用它；
   * 真正把它从打分里剔掉的地方在 {@code RecommendMapper#listExposedPostIds}（去重）
   * 和融合权重（W_REPEAT）。</p>
   *
   * <p>{@code mood_valence} 只有打卡那批行为带得上，其余为 NULL（不是 0）——
   * 与 {@link UserActionMapper#upsert} 里「COALESCE 才覆盖」那条同一语义。</p>
   */
  @Select("SELECT user_id, target_id AS post_id, action_type, weight, mood_valence, created_at "
      + "FROM user_action WHERE target_type = 'post' AND deleted = 0 "
      + "AND created_at >= #{since} ORDER BY user_id, created_at")
  List<ActionRow> listPostActions(@Param("since") LocalDateTime since);

  /**
   * 已过审话题挂在帖子上的标签集合（内容相似度与「因为你常看 X 话题」的理由都读它）。
   *
   * <p>{@code post_topic} <b>没有</b>逻辑删除列（DDL 只有 id/post_id/topic_id/created_at），
   * 所以这里不写 {@code pt.deleted = 0} —— 写了会直接 1054。删除关系靠物理删行表达，
   * 帖子本身的可推荐性由调用方的候选池过滤（{@link #listRecommendablePosts}）负责，
   * 两件事不在这条 SQL 里混。</p>
   *
   * <p>{@code t.deleted = 0 AND t.audit_status = 'APPROVED'} 是必须的：待审话题的名字
   * 一旦进了推荐理由，就等于把「还没被放行的话题」通过推荐文案公示给了全广场。</p>
   */
  @Select("SELECT pt.post_id, t.name AS topic_name FROM post_topic pt "
      + "JOIN topic t ON t.id = pt.topic_id "
      + "WHERE t.deleted = 0 AND t.audit_status = 'APPROVED'")
  List<PostTagRow> listApprovedPostTags();

  /**
   * 每条帖子下已发布评论的安慰反馈读数（创新点②「被验证的安慰价值」的聚合源）。
   *
   * <p>口径与 {@code EmotionBoost#comfortFromReactions} 逐字对齐：
   * joy / trust 两类算「正向接受」，total=0 表示「没人评论过 = 未被验证」，
   * Java 侧返回 null 而不是 0，因为 0 会被读成「被验证为无安慰价值」。</p>
   *
   * <p>只统计 {@code status='PUBLISHED'} 的评论：被驳回的评论是内容安全链的产物，
   * 不能反过来参与「这条帖安慰到了人」的证据。{@code deleted=0} 判的是 comment 表自己的
   * 逻辑删除位（它有这一列），删除的评论连「有人回复过」这个分母都不该贡献。</p>
   */
  @Select("SELECT c.post_id, "
      + "SUM(CASE WHEN c.emotion_primary IN ('joy','trust') THEN 1 ELSE 0 END) AS positive_cnt, "
      + "COUNT(*) AS total_cnt FROM comment c "
      + "WHERE c.deleted = 0 AND c.status = 'PUBLISHED' GROUP BY c.post_id")
  List<ComfortRow> listCommentReactions();

  /**
   * 回填 post.quality_score（任务 T7.5 · 手册 §10.2 第 7.5 条 · 需求 §6.2 质量分）。
   *
   * <p>公式：{@code clamp01( (赞×3 + 评论×4 + 收藏×5 - 举报×5) / (浏览 + 50) )}，
   * 与 {@code RecConstants.W_QUALITY} 一起构成融合式的第二项。分母 +50 是贝叶斯平滑，
   * 让「3 个赞 0 浏览」的新帖不会顶到 1.0 把老帖全挤下去。</p>
   *
   * <p><b>BR4：排除作者自己</b>。三个子计数都用 {@code user_id <> p.user_id} 剔掉自赞、
   * 自藏、自评，否则发帖人开三个小号给自己刷分就能把自己推上广场 —— 需求 §6.3 把这条
   * 写成了硬约束，不是「建议」。</p>
   *
   * <p>用三条相关子查询而不是 {@code LEFT JOIN (GROUP BY)} 派生表：派生表要按 post_id 聚合，
   * 而「非作者」的条件里 {@code p.user_id} 是<b>外层行</b>的值，派生表内部看不见它。
   * 帖子量级（万级）下相关子查询各扫一次 uk_target 索引可接受，且这是离线作业，不在 P95 路径上。</p>
   *
   * <p>只刷 {@code status='PUBLISHED'}：草稿和驳回件的读数是残缺的，写进质量分列
   * 会让它们在被误判为「已发布」的瞬间带着脏分进候选池。</p>
   *
   * <p><b>两个 CAST 不是风格，是修 bug</b>：MySQL 的 {@code COUNT()} 返回 {@code BIGINT UNSIGNED}，
   * {@code post.report_cnt}、{@code view_cnt} 的列类型也是 {@code int unsigned}，而只要有一个操作数是
   * 无符号，结果就按无符号算。于是「零互动但被举报过一次」的帖要算 {@code 0 - 5}，直接抛
   * {@code Data truncation: BIGINT UNSIGNED value is out of range}，整条 UPDATE 失败 ——
   * 表现是离线作业每轮都死在这一步、{@code recommend_result} 永远 0 行、全站默默走热度兜底。
   * 这条由 Gate7 第一次真机重算抓出（阶段 7 轮 18）：此前只有 Java 侧单测跑过，
   * 没人拿真库的脏数据边界验过这条 SQL。</p>
   */
  @Update("UPDATE post p SET p.quality_score = ROUND(LEAST(1, GREATEST(0, ("
      + "CAST((SELECT COUNT(*) FROM post_like l WHERE l.target_type = 'post' "
      + "AND l.action_type = 'LIKE' "
      + "AND l.deleted = 0 AND l.target_id = p.id AND l.user_id <> p.user_id) * 3"
      + " + (SELECT COUNT(*) FROM comment c WHERE c.deleted = 0 AND c.status = 'PUBLISHED' "
      + "AND c.post_id = p.id AND c.user_id <> p.user_id) * 4"
      + " + (SELECT COUNT(*) FROM post_like l2 WHERE l2.target_type = 'post' "
      + "AND l2.action_type = 'COLLECT' AND l2.deleted = 0 AND l2.target_id = p.id "
      + "AND l2.user_id <> p.user_id) * 5 AS SIGNED)"
      + " - CAST(p.report_cnt AS SIGNED) * 5) / (p.view_cnt + 50))), 4) "
      + "WHERE p.deleted = 0 AND p.status = 'PUBLISHED'")
  int backfillQualityScores();

  /**
   * 重算 topic.hot_score（任务 T7.13 · 需求 §6.2 话题热度，话题墙排序与推荐理由都用它）。
   *
   * <p>公式：{@code 近 7 天新帖数 × 3 + 近 7 天行为权重和 × 0.5 + 关注数 × 0.2}。
   * 三个量纲不同的信号各配一个系数，是因为「发帖」比「点赞」更能代表一个话题正在被讨论，
   * 而关注数是长期存量 —— 它的权重最小，防止一个僵尸话题靠历史关注霸榜。</p>
   *
   * <p>新帖只统计 {@code PUBLISHED} 且 {@code published_at >= since}：待审帖的
   * published_at 是 NULL，比较结果是 NULL 而不是 true，条件写 {@code >=} 已把它排除，
   * 但仍显式写上 status 判据，避免「以后有人给待审帖补了发布时间」时热度被灌水。</p>
   *
   * <p>行为维度只取 {@code target_type='topic'}（关注话题的行为打在这个维度上，
   * 见 {@code UserActionCatalog#TARGET_TOPIC}）。</p>
   *
   * <p>{@code LEFT JOIN} + {@code COALESCE}：从没被发过帖的话题要写 0 而不是 NULL ——
   * {@code topic.hot_score} 列是 {@code NOT NULL DEFAULT}，写 NULL 会让整条 UPDATE 直接报错。</p>
   */
  @Update("UPDATE topic t LEFT JOIN ("
      + "SELECT pt.topic_id AS topic_id, COUNT(*) AS new_cnt FROM post_topic pt "
      + "JOIN post p ON p.id = pt.post_id "
      + "WHERE p.deleted = 0 AND p.status = 'PUBLISHED' AND p.published_at >= #{since} "
      + "GROUP BY pt.topic_id) nw ON nw.topic_id = t.id "
      + "LEFT JOIN (SELECT target_id AS topic_id, SUM(weight) AS w_sum FROM user_action "
      + "WHERE target_type = 'topic' AND deleted = 0 AND created_at >= #{since} "
      + "GROUP BY target_id) act ON act.topic_id = t.id "
      + "SET t.hot_score = ROUND(COALESCE(nw.new_cnt, 0) * 3 + COALESCE(act.w_sum, 0) * 0.5 "
      + "+ t.follow_cnt * 0.2, 4) WHERE t.deleted = 0")
  int recomputeTopicHotScores(@Param("since") LocalDateTime since);

  /**
   * 窗口内有行为的活跃用户（离线作业「给谁算推荐」的名单，需求 §6.2「按活跃度分批」）。
   *
   * <p>只取有行为的人：全站注册但零行为的用户走 {@code ColdStart} 的冷启动分支，
   * 那份名单不需要出现在这里（在线侧 {@code FeedService} 发现无缓存就退热度兜底）。
   * 反过来，给一万个从没点过赞的人每 30 分钟各算 200 条候选，是把离线作业写成 DoS。</p>
   */
  @Select("SELECT DISTINCT user_id FROM user_action WHERE deleted = 0 "
      + "AND created_at >= #{since} ORDER BY user_id")
  List<Long> listActiveUserIds(@Param("since") LocalDateTime since);

  /** 某用户窗口内的帖子行为条数 —— {@code ColdStart#cfEligible} 的入参（任务 T7.12）。 */
  @Select("SELECT COUNT(*) FROM user_action WHERE user_id = #{userId} AND target_type = 'post' "
      + "AND deleted = 0 AND created_at >= #{since}")
  long countPostActionsOfUser(@Param("userId") long userId,
      @Param("since") LocalDateTime since);

  /**
   * 该用户最近一次打卡采集到的心情效价（需求 §6.2 创新点②在线侧唯一的输入）。
   *
   * <p>返回 {@code Integer} 而不是 {@code int}：NULL 的语义是「从没采到过」，
   * 与 0（中性心情）完全不同。把 NULL 读成 0 会让「没打卡的用户」全体被当成心情中性，
   * 进而让情绪加权对所有人均匀生效，等于没有这一路。</p>
   *
   * <p>{@code ORDER BY created_at DESC LIMIT 1}：用的是最近一次心情，不是平均心情 ——
   * 需求 §6.2 的理由是「此刻的情绪」，历史平均会把「上周很难过」的人一直按低落加权。</p>
   */
  @Select("SELECT mood_valence FROM user_action WHERE user_id = #{userId} "
      + "AND target_type = 'post' AND mood_valence IS NOT NULL AND deleted = 0 "
      + "ORDER BY created_at DESC LIMIT 1")
  Integer latestMoodValence(@Param("userId") long userId);

  /** 窗口内已曝光过的帖子（需求 §6.2「N 天内不重复推荐」的排除集）。 */
  @Select("SELECT DISTINCT target_id FROM user_action WHERE user_id = #{userId} "
      + "AND target_type = 'post' AND action_type = 'expose' AND deleted = 0 "
      + "AND created_at >= #{since}")
  List<Long> listExposedPostIds(@Param("userId") long userId,
      @Param("since") LocalDateTime since);

  /**
   * 窗口内被用户负反馈过的帖子（dislike + report，需求 FR1.7「减少此类推荐」）。
   *
   * <p>两种行为并成一个集合，因为它们在召回阶段的处置相同：整体排除。
   * 区别只在权重上（dislike 是「不喜欢这个内容」，report 是「这个内容有害」），
   * 而那个区别已经体现在 {@code user_action.weight} 的负值里，参与的是打分而不是过滤。</p>
   */
  @Select("SELECT DISTINCT target_id FROM user_action WHERE user_id = #{userId} "
      + "AND target_type = 'post' AND action_type IN ('dislike','report') AND deleted = 0 "
      + "AND created_at >= #{since}")
  List<Long> listNegativePostIds(@Param("userId") long userId,
      @Param("since") LocalDateTime since);

  /**
   * 候选池：此刻可被推荐的帖子（质量分倒序，截断到 {@code RecConstants.CANDIDATE_POOL_CAP}）。
   *
   * <p>四道闸，一条都不能少：帖子未删 + 已发布 + 公开可见 + 树洞未到期销毁。</p>
   *
   * <p>第五道闸是 {@code JOIN user}：作者注销（status='DELETED'）或封禁的帖子不能进候选池。
   * {@code PostQueryService#applyAuthorActive} 在读侧也判同一件事，这里提前判是为了
   * 不把注定被过滤掉的行占掉 Top-N 的名额 —— 否则一屏 20 条里可能有 5 条组装时就被丢了。</p>
   *
   * <p><b>{@code u.status = 'ACTIVE'} 是字符串枚举</b>，不是手册 §7.5 示意 DDL 里的
   * {@code status = 0}。真实列是 {@code enum('ACTIVE','MUTED','BANNED','DELETED')}，
   * 写 0 会在 MySQL 非严格模式下匹配到<b>枚举的第一个值</b>，等于「所有人都算活跃」，
   * 而且不报错。偏差已写进 {@code PostQueryService} 第 163 行注释。</p>
   *
   * <p>{@code visibility = 'public'} 排掉 private 与 friends：树洞的匿名帖虽然发布在广场上
   * 是 public，自己的私密日记不是。<b>故意不选 content 列</b> —— 候选池 300 行 × mediumtext
   * 会把全表正文拉进堆，而离线作业只需要 id 和几个读数。</p>
   */
  @Select("SELECT p.id, p.user_id, p.type, p.published_at, p.quality_score, p.emotion_primary, "
      + "p.risk_level, p.is_anonymous FROM post p JOIN `user` u ON u.id = p.user_id "
      + "WHERE p.deleted = 0 AND p.status = 'PUBLISHED' AND p.visibility = 'public' "
      + "AND (p.auto_destroy_at IS NULL OR p.auto_destroy_at > #{now}) "
      + "AND u.deleted = 0 AND u.status = 'ACTIVE' "
      + "ORDER BY p.quality_score DESC, p.published_at DESC LIMIT #{limit}")
  List<PostMetaRow> listRecommendablePosts(@Param("limit") int limit,
      @Param("now") LocalDateTime now);

  /**
   * 按 id 复核可推荐性（相似位在线侧的池内过滤，任务 T7.16 · 手册 §10.6 第 2 条）。
   *
   * <p>五条门槛与 {@link #listRecommendablePosts} 逐字同一份：帖子未删、已发布、公开、
   * 树洞未到期、作者账号 ACTIVE。之所以在在线侧再判一次，是因为缓存里存的是
   * <b>离线那一刻</b>的邻居：两批之间作者删了帖、被封了号、把帖子改成私密，
   * {@code item_similarity} 不会知道，而 按帖子事件即时失效邻居行的钩子
   * 本阶段没有做（需求 §6.2 只要求「下一批重算时消失」，手册 §10.6 第 5 条）。
   * 但「下一批」最坏是 30 分钟，危机帖与已注销作者的帖不能等这 30 分钟，
   * 所以判据每次请求现查，只有<b>排序结果</b>进缓存。</p>
   *
   * <p>{@code risk_level} 在这条 SQL 里<b>不</b>筛（与 {@link #listPostsByTopicName} 不同）：
   * 拦 L2/L3 的动作在 Java 侧做，为的是「本条相似位拦了几条危机帖」能进日志与单测，
   * 塞进 SQL 就成了静默消失。也正因为要取到它做判断，这一列才必须 SELECT 出来。</p>
   *
   * <p>IN 列表由调用方保证非空（空列表拼出 {@code IN ()} 是 1064），
   * 上限是候选池 {@code CANDIDATE_CAP}=40，走 {@code post} 主键不慢。</p>
   */
  @Select("<script>SELECT p.id, p.user_id, p.type, p.published_at, p.quality_score, "
      + "p.emotion_primary, p.risk_level, p.is_anonymous FROM post p "
      + "JOIN `user` u ON u.id = p.user_id "
      + "WHERE p.deleted = 0 AND p.status = 'PUBLISHED' AND p.visibility = 'public' "
      + "AND (p.auto_destroy_at IS NULL OR p.auto_destroy_at &gt; #{now}) "
      + "AND u.deleted = 0 AND u.status = 'ACTIVE' "
      + "AND p.id IN <foreach collection='ids' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>"
      + "</script>")
  List<PostMetaRow> listRecommendableByIds(@Param("ids") List<Long> ids,
      @Param("now") LocalDateTime now);

  /** 单帖的话题名（推荐理由与内容相似度的标签集合）。 */
  @Select("SELECT t.name FROM post_topic pt JOIN topic t ON t.id = pt.topic_id "
      + "WHERE pt.post_id = #{postId} AND t.deleted = 0 AND t.audit_status = 'APPROVED' "
      + "ORDER BY t.name")
  List<String> listPostTopicNames(@Param("postId") long postId);

  /**
   * 同话题的其它帖子（相似位兜底：邻居缓存缺失或邻居全不可用时用，任务 T7.14）。
   *
   * <p>带 <b>{@code risk_level IN ('L0','L1')}</b> 这一条（手册 §10.6 第 2 条）：
   * L2/L3 是危机干预级别的帖子，把它们挂在「你可能也想看」的位置上，
   * 等于在一个刚表达过自伤念头的用户屏幕里再塞一条同类内容 —— 这是需求 §8.3
   * 明令禁止的二次暴露，优先级高于「相似度高」。</p>
   *
   * <p>也带上 {@code excludeId}：相关推荐里出现「和这条帖相似：这条帖」是循环推荐，
   * 而且用户会以为自己刷到了两条一样的内容。</p>
   */
  @Select("SELECT p.id FROM post p JOIN post_topic pt ON pt.post_id = p.id "
      + "JOIN topic t ON t.id = pt.topic_id "
      + "WHERE t.name = #{topicName} AND t.deleted = 0 AND t.audit_status = 'APPROVED' "
      + "AND p.id <> #{excludeId} AND p.deleted = 0 AND p.status = 'PUBLISHED' "
      + "AND p.visibility = 'public' AND p.risk_level IN ('L0','L1') "
      + "AND (p.auto_destroy_at IS NULL OR p.auto_destroy_at > #{now}) "
      + "ORDER BY p.quality_score DESC, p.published_at DESC LIMIT #{limit}")
  List<Long> listPostsByTopicName(@Param("topicName") String topicName,
      @Param("excludeId") long excludeId, @Param("now") LocalDateTime now,
      @Param("limit") int limit);

  /**
   * 注册引导里用户自选的兴趣标签（{@code user_profile.interest_tags}，JSON 数组字符串）。
   *
   * <p>返回原始 JSON 文本而不是 List：MyBatis 侧要配 TypeHandler 才能直接映射成集合，
   * 而本项目在 {@code PostImage} 等 JSON 列上的既有口径同样是「取字符串、Jackson 在
   * Service 层解」。解析失败（脏数据）由 Service 兜成空列表，不影响整批推荐。</p>
   *
   * <p>这一列是<b>冷启动主信号</b>（{@code ColdStart#firstChannel} 的 hasTagSignal）：
   * 新用户没有任何行为，但注册时勾过「失眠」「考研压力」，就够走内容通道。</p>
   */
  @Select("SELECT interest_tags FROM user_profile WHERE user_id = #{userId} AND deleted = 0")
  String listInterestTags(@Param("userId") long userId);

  /**
   * 该用户关注的话题名（内容通道的第二个输入，与兴趣标签同权）。
   *
   * <p>{@code topic_follow} <b>没有</b>逻辑删除列（DDL 只有 id/user_id/topic_id/created_at），
   * 取关是物理 DELETE，所以这里不写、也写不了 {@code tf.deleted = 0}。
   * 话题自身的 {@code deleted=0 + APPROVED} 必须判：待审话题名进推荐理由等于公示。</p>
   */
  @Select("SELECT t.name FROM topic_follow tf JOIN topic t ON t.id = tf.topic_id "
      + "WHERE tf.user_id = #{userId} AND t.deleted = 0 AND t.audit_status = 'APPROVED' "
      + "ORDER BY t.hot_score DESC, t.name")
  List<String> listFollowedTopicNames(@Param("userId") long userId);

  /** 某个话题名下的热度读数（AdminRecController 抽查「这个话题为什么热」）。 */
  @Select("SELECT hot_score FROM topic WHERE name = #{name} AND deleted = 0 LIMIT 1")
  BigDecimal topicHotScore(@Param("name") String name);
}