package com.mindisle.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.RecommendResult;

/**
 * 推荐结果缓存 Mapper（任务 T7.6 / T7.9 / T7.10 · 手册 §10.1 在线侧）。
 *
 * <p>写入只来自离线作业，读取只来自 {@code FeedService} 与 {@code SimilarPostService}，
 * 所以这里没有「按分数排序取候选」这类召回 SQL —— 那些属于 {@code RecommendMapper}，
 * 动的是真相表（user_action / post / topic），不是这张缓存表。</p>
 *
 * <p><b>重算是「删了再插」，不是 upsert</b>：{@code uk_user_scene_item_mode} 只能保证
 * 「同一模式里不会给同一帖记两行」，管不了「上一批有 200 行、这一批只算出 30 行」时的
 * 剩余 170 行。留着的后果是在线侧会读到陈旧候选并给它们重新编号 position，
 * 用户翻第二页看到的第一条可能是三天前的结果。所以 {@link #deleteBatch} 是物理删。</p>
 *
 * <p><b>物理删除的边界</b>：只有这一张缓存表允许物理删。理由写死在这里 ——
 * 需求 §12 规范 5 的「行为留痕」落在 user_action（曝光、点赞、点不感兴趣都在那儿），
 * 缓存行本身不是用户行为，删它不丢任何审计事实；反过来若给它留 deleted=1 的墓碑，
 * 每 30 分钟一批 × 全站用户，这张表会无限膨胀而没人读得到那些行。</p>
 */
@Mapper
public interface RecommendResultMapper extends BaseMapper<RecommendResult> {

  /**
   * 批量插入一批结果。调用方负责在插入前 {@link #deleteBatch}，以及把 position 编成连续序号。
   *
   * <p>{@code is_exposed} / {@code exposed_at} 一律写 0 / NULL：曝光只能由在线侧
   * {@link #markExposed} 置位。离线重算把旧行的曝光状态带过来是错的 —— 那等于
   * 「新算出来的这一批替用户承认了他没见过」，去重窗口当场失效。</p>
   *
   * <p>{@code reason} 可为 NULL（热度兜底通道拿不出「因为你看过的某帖」这种理由）。
   * 列本身是 NULL 允许，D6 的验收是「卡片显示推荐理由」，前端对 null 不渲染那一行。</p>
   */
  @Insert("<script>INSERT INTO recommend_result (user_id, scene, item_id, score, recall_channel, "
      + "reason, mode, position, is_exposed, exposed_at, calc_at, deleted) VALUES "
      + "<foreach collection='rows' item='r' separator=','>"
      + "(#{r.userId}, #{r.scene}, #{r.itemId}, #{r.score}, #{r.recallChannel}, #{r.reason}, "
      + "#{r.mode}, #{r.position}, 0, NULL, #{r.calcAt}, 0)"
      + "</foreach></script>")
  int batchInsert(@Param("rows") List<RecommendResult> rows);

  /**
   * 清掉某用户某场景某模式的旧批次（物理删，见类注释）。
   *
   * <p>三个条件都带全：少带 {@code mode} 会在 AB 实验期把对照组的候选一起删掉，
   * 需求 §6.4 的消融对比数据就没了。</p>
   */
  @Delete("DELETE FROM recommend_result WHERE user_id = #{userId} AND scene = #{scene} "
      + "AND mode = #{mode}")
  int deleteBatch(@Param("userId") long userId, @Param("scene") String scene,
      @Param("mode") String mode);

  /**
   * 按位置取一屏（在线读缓存，手册 §10.1「在线只查表」）。
   *
   * <p>走 {@code idx_user_scene_pos(user_id, scene, position)}：ORDER BY position 与索引
   * 第三列同向，LIMIT 直接在索引有序区间上截断，不回表排序。</p>
   *
   * <p>{@code deleted} 由 {@code @TableLogic} 之外手写：这条是裸 SQL，
   * MyBatis-Plus 的逻辑删除只自动作用于 Wrapper/BaseMapper 生成的语句。</p>
   */
  @Select("SELECT id, user_id, scene, item_id, score, recall_channel, reason, mode, position, "
      + "is_exposed, exposed_at, calc_at, deleted, created_at, updated_at "
      + "FROM recommend_result WHERE user_id = #{userId} AND scene = #{scene} AND mode = #{mode} "
      + "AND deleted = 0 ORDER BY position ASC LIMIT #{offset}, #{size}")
  List<RecommendResult> selectByPosition(@Param("userId") long userId, @Param("scene") String scene,
      @Param("mode") String mode, @Param("offset") int offset, @Param("size") int size);

  /**
   * 标记曝光：把实际发给用户的那几条置 is_exposed=1 并记首次时刻。
   *
   * <p>{@code exposed_at} 用 {@code COALESCE(exposed_at, #{now})} 而不是直接覆盖：
   * 语义是<b>首次</b>曝光时间（DDL 注释逐字写着），同一批结果被下拉刷新第二次命中时
   * 覆盖它会让 {@code rec.expose_dedup_days} 的去重窗口跟着刷新、永远滑不到头。</p>
   *
   * <p>IN 列表由调用方保证非空。空列表拼出 {@code IN ()} 是 1064。</p>
   */
  @Update("<script>UPDATE recommend_result SET is_exposed = 1, "
      + "exposed_at = COALESCE(exposed_at, #{now}) "
      + "WHERE user_id = #{userId} AND scene = #{scene} AND mode = #{mode} AND deleted = 0 "
      + "AND item_id IN <foreach collection='itemIds' item='i' open='(' separator=',' close=')'>#{i}"
      + "</foreach></script>")
  int markExposed(@Param("userId") long userId, @Param("scene") String scene,
      @Param("mode") String mode, @Param("itemIds") List<Long> itemIds,
      @Param("now") LocalDateTime now);

  /**
   * 用户点「不感兴趣」：只逻辑删除这一行（需求 FR1.7「不感兴趣，减少此类推荐」）。
   *
   * <p><b>为什么不物理删</b>：这一行是「算法在什么时候、以多少分、用什么理由把这条推给了
   * 谁」的唯一记录。用户负反馈之后，论文要拿这批行算「推荐错误的代价」，运营要看
   * 「同一通道被驳回几次」——都要求它还在。真正的行为事实在
   * user_action(dislike)，由 {@code UserActionRecorder} 写，两者不互相替代。</p>
   *
   * <p>不带 mode：用户在界面上看不出自己属于哪个 AB 分组，一次「不感兴趣」就应当
   * 把它在该用户该场景的所有批次里一起压掉，否则翻到第二页又看见同一条。</p>
   */
  @Update("UPDATE recommend_result SET deleted = 1 "
      + "WHERE user_id = #{userId} AND scene = #{scene} AND item_id = #{itemId} AND deleted = 0")
  int dismiss(@Param("userId") long userId, @Param("scene") String scene,
      @Param("itemId") long itemId);

  /**
   * 批量「不感兴趣」：把同一用户同一场景下若干条结果的行一起逻辑删除（任务 T7.7）。
   *
   * <p>存在理由是 D6 的验收要「当场点当场没」：用户驳回一条帖之后，
   * 与它相似的那批邻居也得一起压掉，否则下一屏还会看见"换了个封面的同一条"，
   * 而邻居的重算最坏要等 30 分钟。行为事实仍然只写一条 dislike 到 user_action，
   * 这里删的是<b>缓存行</b>，两者不互相替代（与 {@link #dismiss} 同一口径）。</p>
   *
   * <p>同样<b>不带 mode</b>：用户看不出自己属于哪个 AB 分组，一次驳回应当把它在该用户
   * 该场景的所有批次里一起压掉（{@link #dismiss} 的注释逐字写着这条）。
   * 也不带 {@code is_exposed} 条件：已曝光的更要压掉，那正是用户刚看见并拒绝的。</p>
   *
   * <p>IN 列表由调用方保证非空（空 {@code IN ()} 是 1064）。返回受影响行数，
   * 调用方用它做日志，不用它判成功：0 行是常态（邻居本来就可能在别的批次里）。</p>
   */
  @Update("<script>UPDATE recommend_result SET deleted = 1 "
      + "WHERE user_id = #{userId} AND scene = #{scene} AND deleted = 0 "
      + "AND item_id IN <foreach collection='itemIds' item='i' open='(' separator=',' close=')'>#{i}"
      + "</foreach></script>")
  int dismissItems(@Param("userId") long userId, @Param("scene") String scene,
      @Param("itemIds") List<Long> itemIds);

  /** 这批缓存是什么时候算的（在线侧据此判 TTL；null = 从没算过）。 */
  @Select("SELECT MAX(calc_at) FROM recommend_result WHERE user_id = #{userId} "
      + "AND scene = #{scene} AND mode = #{mode} AND deleted = 0")
  LocalDateTime lastCalcAt(@Param("userId") long userId, @Param("scene") String scene,
      @Param("mode") String mode);

  /** 缓存里还有多少条可用（AdminRecController 健康度；0 = 该用户需要兜底）。 */
  @Select("SELECT COUNT(*) FROM recommend_result WHERE user_id = #{userId} AND scene = #{scene} "
      + "AND mode = #{mode} AND deleted = 0 AND is_exposed = 0")
  long countUnexposed(@Param("userId") long userId, @Param("scene") String scene,
      @Param("mode") String mode);

  /** 全站缓存行数（D7 日志与 AdminRecController 状态都要用这个数判「作业有没有真的写过」）。 */
  @Select("SELECT COUNT(*) FROM recommend_result WHERE deleted = 0")
  long countActive();
}