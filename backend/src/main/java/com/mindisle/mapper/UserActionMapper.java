package com.mindisle.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.UserAction;

/**
 * 行为埋点 Mapper（任务 T3.10 / T4.19）。
 *
 * <p>只有两条裸 SQL，其余读写走 {@link BaseMapper}。裸 SQL 之所以必要，是因为这两条要动的东西
 * Wrapper 表达不出来：① 撞 {@code uk_action} 时<b>覆盖</b>而不是报错或忽略；
 * ② 撤销行为时写逻辑删除位（{@code @TableLogic} 只会给查询自动追加 deleted = 0，
 * 更新语句里的 deleted 得自己写）。跨表读 emotion_record 算心情不在本 Mapper 里——
 * 一张表只归一个 Mapper 管（{@link EmotionRecordMapper#latestCheckinMood}），这与
 * {@link PostLikeMapper} 的取舍口径一致。</p>
 *
 * <p><b>为什么 upsert 而不是 INSERT IGNORE</b>：{@code post_like} 用 IGNORE 是对的，
 * 因为撞键意味着「这个人已经赞过这条」，第二行本来就该丢掉。埋点表不同——同一行里除了
 * 「谁对谁做了什么」之外还带着<b>会变的读数</b>：停留时长、当时的心情、这次来自哪个场景。
 * 用 IGNORE 的话，用户第二次打开同一条帖停了 40 秒，这一行还停在第一次的 3 秒，
 * 「完读」永远进不了表。所以撞键要更新，而更新哪些列必须有明确规则（见 {@link #upsert}）。</p>
 */
@Mapper
public interface UserActionMapper extends BaseMapper<UserAction> {

  /**
   * 幂等写入：撞 uk_action(user_id, target_type, target_id, action_type, day_bucket) 时覆盖读数。
   *
   * <p><b>四条覆盖规则，各有一句理由</b>：</p>
   * <ul>
   *   <li>weight / message_id / scene / deleted —— 直接覆盖。其中 deleted = 0 让
   *       「取消赞之后再赞」复用同一行而不是插第二行，与
   *       {@link PostLikeMapper#reviveCancelled} 同一语义。</li>
   *   <li>mood_valence —— <b>新值非 NULL 才覆盖</b>。NULL 的含义是「此刻没采到心情」，
   *       <b>不是</b>「心情为零」（见 {@link UserAction#moodValence}）；无条件覆盖会让一天里
   *       「先打过卡、后来又触发一次不带 mood 的行为」把唯一的读数抹成 NULL，
   *       而创新点②要用的正是那一次读数。</li>
   *   <li>duration_ms —— <b>取历史最大值</b>。同日重复进入命中的是同一条 uk，
   *       「以最后一次为准」会让第一次停 40 秒、第二次手滑 3 秒把
   *       {@code UserActionCatalog#VIEW_MIN_DURATION_MS} 这条判据吃掉：停留时长是
   *       「至少到过多少」的下界读数，不是「此刻读数」，取 max 才是它的语义。
   *       代价是这一列表达不了「用户后来只看了 1 秒」，而那个信息本来也不在需求口径里。</li>
   *   <li>created_at —— <b>不动</b>（不在 UPDATE 子句里，由第一次插入的 DEFAULT 决定）。
   *       它是需求 §6.2 时间衰减的基准，语义是「这个人第一次对这个物品产生这个行为」的时刻；
   *       每次刷新会让长期反复看的内容永远新鲜，时间衰减直接失效。</li>
   * </ul>
   *
   * <p>VALUES(col) 写法自 MySQL 8.0.20 起被标记废弃（推荐行别名 AS new），
   * 本机 MySQL 9.7 实测可用，且 {@link WeeklyReportMapper} 的 upsert 已是同一写法——
   * 一个项目里并存两种 upsert 方言比废弃警告本身更糟。</p>
   */
  @Insert("INSERT INTO user_action (user_id, target_type, target_id, action_type, weight, "
      + "mood_valence, message_id, day_bucket, scene, duration_ms, deleted) "
      + "VALUES (#{userId}, #{targetType}, #{targetId}, #{actionType}, #{weight}, "
      + "#{moodValence}, #{messageId}, #{dayBucket}, #{scene}, #{durationMs}, 0) "
      + "ON DUPLICATE KEY UPDATE "
      + "weight = VALUES(weight), "
      + "message_id = VALUES(message_id), "
      + "scene = VALUES(scene), "
      + "deleted = 0, "
      + "mood_valence = COALESCE(VALUES(mood_valence), mood_valence), "
      + "duration_ms = CASE WHEN duration_ms IS NULL THEN VALUES(duration_ms) "
      + "WHEN VALUES(duration_ms) IS NULL THEN duration_ms "
      + "WHEN VALUES(duration_ms) > duration_ms THEN VALUES(duration_ms) "
      + "ELSE duration_ms END")
  int upsert(@Param("userId") long userId, @Param("targetType") String targetType,
      @Param("targetId") long targetId, @Param("actionType") String actionType,
      @Param("weight") BigDecimal weight, @Param("moodValence") Integer moodValence,
      @Param("messageId") Long messageId, @Param("dayBucket") LocalDate dayBucket,
      @Param("scene") String scene, @Param("durationMs") Integer durationMs);

  /**
   * 撤销一次行为：把该用户对这个目标的全部活动行置为逻辑删除（取消赞 / 取消藏 / 取关）。
   *
   * <p><b>软删而不是物理删</b>：需求 §12 规范 5 要「行为留痕」，而阶段 7 的曝光分母与
   * 论文的「用户活跃天数」都要读到这行存在过。返回 0 表示「本来就没有活动行」，不是错误——
   * 用户连点两次「取消点赞」是正常操作。</p>
   *
   * <p><b>不带 day_bucket、也不带 LIMIT</b>：与 {@link PostLikeMapper#cancelActive} 同一取舍——
   * 跨日的历史行同样是「这个人对这条内容点过赞」的留痕，一次取消要把它们一起置掉；
   * 逐行处理会让两次点击落在不同的行上，出现「取消了一个、另一个还在」的半截状态。</p>
   */
  @Update("UPDATE user_action SET deleted = 1 "
      + "WHERE user_id = #{userId} AND target_type = #{targetType} "
      + "AND target_id = #{targetId} AND action_type = #{actionType} AND deleted = 0")
  int cancelActive(@Param("userId") long userId, @Param("targetType") String targetType,
      @Param("targetId") long targetId, @Param("actionType") String actionType);
}
