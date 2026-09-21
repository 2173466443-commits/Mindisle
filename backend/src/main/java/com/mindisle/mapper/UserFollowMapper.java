package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.UserFollow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 关注关系 Mapper（任务 3.6 · 需求 FR4.6「关注/取关」）。
 *
 * <p>{@code user_follow} 没有逻辑删除列，所以取关是物理 DELETE ——
 * 理由写在 {@link UserFollow} 的类注释里（唯一索引不认逻辑删除位）。
 * 幂等靠 uk_follow_pair + INSERT IGNORE：重复关注返回 0 行而不是 1062 报错，
 * 取消一个没关注的关系也返回 0 行而不是「取消失败」。两个方向都<b>不报错</b>，
 * 因为「点了两次关注按钮」和「网络重试」在用户侧都不是错误。</p>
 */
@Mapper
public interface UserFollowMapper extends BaseMapper<UserFollow> {

  /** 幂等关注：撞 uk_follow_pair 返回 0。 */
  @Insert("INSERT IGNORE INTO user_follow (user_id, follow_user_id) VALUES (#{userId}, #{targetUserId})")
  int insertIgnore(@Param("userId") long userId, @Param("targetUserId") long targetUserId);

  /** 取关：0 行表示本来就没关注。 */
  @Delete("DELETE FROM user_follow WHERE user_id = #{userId} AND follow_user_id = #{targetUserId}")
  int deletePair(@Param("userId") long userId, @Param("targetUserId") long targetUserId);

  /** 关注数（我关注了多少人）。 */
  @Select("SELECT COUNT(*) FROM user_follow WHERE user_id = #{userId}")
  long countFollowing(@Param("userId") long userId);

  /** 粉丝数（多少人关注了我）。走 idx_follow_user 前缀。 */
  @Select("SELECT COUNT(*) FROM user_follow WHERE follow_user_id = #{userId}")
  long countFollowers(@Param("userId") long userId);

  /**
   * 把两个计数刷新进 user_profile 的冗余列（DDL 注释「Redis 计数回写 BR2」的那两列）。
   *
   * <p><b>用 INSERT ... ON DUPLICATE KEY UPDATE 而不是 UPDATE</b>：
   * user_profile 与 user 是 1 对 1，但注册链路早于本任务，历史数据里不保证每人都有一行资料。
   * 走 UPDATE 时「0 行受影响」既可能是没行也可能是值没变，读侧没法区分，
   * 于是 follower_cnt 会静默停留在旧值上；补齐一行只写这两列（其余列取 DDL 默认值）
   * 才能保证「刷了就一定等于真相」。读侧本来就直接 COUNT 真表（见
   * {@link com.mindisle.user.RelationshipService}），所以这两列即便短暂落后也不会显示给用户，
   * 刷新它们是为了管理端与数据大屏（阶段 6 A2）读到同一个数。</p>
   */
  @Insert("INSERT INTO user_profile (user_id, following_cnt, follower_cnt) "
      + "VALUES (#{userId}, #{followingCnt}, #{followerCnt}) "
      + "ON DUPLICATE KEY UPDATE following_cnt = #{followingCnt}, follower_cnt = #{followerCnt}")
  int upsertFollowCounts(@Param("userId") long userId, @Param("followingCnt") long followingCnt,
      @Param("followerCnt") long followerCnt);
}
