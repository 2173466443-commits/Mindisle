package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostLike;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 点赞/收藏 Mapper（任务 3.6 · 需求 BR2 幂等、FR4.4）。
 *
 * <p>只有三条裸 SQL，其余读写走 BaseMapper + LambdaQueryWrapper：
 * {@code @TableLogic} 会给查询自动追加 {@code deleted = 0}，所以「查活动行」不必自己写条件；
 * 而「复活已取消的行」「取消活动行」都要显式操作 deleted 位，Wrapper 反而表达不出来，
 * 这与 {@link PostMapper#increaseViewCnt} 的取舍口径一致。</p>
 *
 * <p><b>为什么 UPDATE 不带 LIMIT</b>：一个 (用户, 目标, 动作) 正常只有一行，
 * 万一历史里真有两行（并发跨日），一次性把它们同时置为活动或同时置为取消才是对的——
 * 逐行处理会让两次操作落在不同的行上，出现「取消了一个、另一个还在」的半截状态。</p>
 */
@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {

  /**
   * 幂等插入：撞 {@code uk_action} 就返回 0 而不是报错。
   *
   * <p>{@code INSERT IGNORE} 会吞掉所有约束错误（包括「目标 id 为 0」这种本该暴露的脏数据），
   * 所以调用方必须先过参数校验，且这里只用于「同用户同目标同动作同日」这一条已知冲突；
   * 单测 {@code PostInteractionServiceTest} 里那个内存 fake 只实现 uk_action 一条约束，
   * 真库上其他约束报错仍会照常抛出——两者行为一致才算测到了东西。</p>
   */
  @Insert("INSERT IGNORE INTO post_like "
      + "(user_id, target_type, target_id, action_type, day_bucket) "
      + "VALUES (#{userId}, #{targetType}, #{targetId}, #{actionType}, #{dayBucket})")
  int insertIgnore(@Param("userId") long userId, @Param("targetType") String targetType,
      @Param("targetId") long targetId, @Param("actionType") String actionType,
      @Param("dayBucket") LocalDate dayBucket);

  /** 复活该用户对这个目标已经取消过的行；返回影响行数，0 表示「从未赞过或早已是活动态」。 */
  @Update("UPDATE post_like SET deleted = 0 "
      + "WHERE user_id = #{userId} AND target_type = #{targetType} "
      + "AND target_id = #{targetId} AND action_type = #{actionType} AND deleted = 1")
  int reviveCancelled(@Param("userId") long userId, @Param("targetType") String targetType,
      @Param("targetId") long targetId, @Param("actionType") String actionType);

  /** 取消：把该用户对这个目标的全部活动行置为逻辑删除。返回 0 即「本来就没赞过」，不报错。 */
  @Update("UPDATE post_like SET deleted = 1 "
      + "WHERE user_id = #{userId} AND target_type = #{targetType} "
      + "AND target_id = #{targetId} AND action_type = #{actionType} AND deleted = 0")
  int cancelActive(@Param("userId") long userId, @Param("targetType") String targetType,
      @Param("targetId") long targetId, @Param("actionType") String actionType);

  /**
   * 计数真相：赞这条目标的<b>人数</b>。
   *
   * <p><b>为什么是 COUNT(DISTINCT user_id) 而不是 COUNT(*)</b>：{@code uk_action} 把
   * day_bucket 也算进了唯一键，所以极端并发下同一人对同一目标可能存在两行活动行
   * （见 {@link com.mindisle.entity.PostLike} 类注释）。数行会让一个人被计成两次，
   * 数人就天然免疫。多一个 distinct 的代价是排序去重，但目标维度上的行数是「这条帖有多少人赞」，
   * 本来就是几百量级，走 idx_target 前缀过滤后不痛。</p>
   */
  @Select("SELECT COUNT(DISTINCT user_id) FROM post_like "
      + "WHERE target_type = #{targetType} AND target_id = #{targetId} "
      + "AND action_type = #{actionType} AND deleted = 0")
  long countActiveUsers(@Param("targetType") String targetType, @Param("targetId") long targetId,
      @Param("actionType") String actionType);
}
