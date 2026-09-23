package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.TopicFollow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 话题关注 Mapper（任务 3.8 · 手册 §6.1 行 3.8「关注话题」）。
 *
 * <p>整套方法与 {@link UserFollowMapper} 一一对应（幂等关注 / 物理取关 / 存在性 / 计数 /
 * 刷新冗余列），这不是复制粘贴的巧合，而是「关注」这个动作在人维度和话题维度上
 * 需要同样的四条保证：重复点击不报错、取关没关注也不报错、状态读得准、计数等于真相。
 * 之所以仍是两份 SQL 而不是抽一个泛型基类：表名与列名都不同，抽出来只会得到
 * 一堆 {@code String tableName} 参数，读的人反而看不出它到底改了哪张表。</p>
 *
 * <p>{@code topic_follow} 没有逻辑删除列，所以取关是物理 DELETE（理由见
 * {@link TopicFollow} 的类注释）。</p>
 */
@Mapper
public interface TopicFollowMapper extends BaseMapper<TopicFollow> {

  /** 幂等关注：撞 uk_topic_follow 返回 0。 */
  @Insert("INSERT IGNORE INTO topic_follow (user_id, topic_id) VALUES (#{userId}, #{topicId})")
  int insertIgnore(@Param("userId") long userId, @Param("topicId") long topicId);

  /** 取关：0 行表示本来就没关注。 */
  @Delete("DELETE FROM topic_follow WHERE user_id = #{userId} AND topic_id = #{topicId}")
  int deletePair(@Param("userId") long userId, @Param("topicId") long topicId);

  /**
   * 是否已关注。用 COUNT 而不是 {@code EXISTS(SELECT 1 ...)}：两件事在这张表上性能相同
   * （都走 uk_topic_follow 的唯一查找），而 COUNT 的返回类型是稳定的 long，
   * 单测里用 JDK 动态代理打桩时不需要猜驱动怎么映射 EXISTS 的结果集。
   */
  @Select("SELECT COUNT(*) FROM topic_follow WHERE user_id = #{userId} AND topic_id = #{topicId}")
  long isFollowing(@Param("userId") long userId, @Param("topicId") long topicId);

  /** 我关注了多少个话题（U11 资料卡与话题页共用这一个数）。 */
  @Select("SELECT COUNT(*) FROM topic_follow WHERE user_id = #{userId}")
  long countByUser(@Param("userId") long userId);

  /**
   * 把 topic.follow_cnt 刷成真相表的重算值（任务 3.8 · 手册 §18 Gate3「计数=关联表 count」）。
   *
   * <p><b>为什么是「重算」而不是 {@code follow_cnt = follow_cnt + 1}</b>：与
   * {@link PostMapper#refreshLikeCnt} 同一条教义 —— 自增计数器一旦和真相表走岔就再也对不上，
   * 而重算让漂移在结构上不可能发生。取关方向更是只有重算能做对：
   * 「删了 0 行」和「删了 1 行」都调这一个方法时，前者会把计数刷回正确的旧值，
   * 后者会减一，不需要调用方把 affected rows 传进来猜。</p>
   *
   * <p>子查询读 topic_follow、更新的是 topic，两张不同的表，不触发
   * {@code ER_UPDATE_TABLE_USED}；{@code topic_id = topic.id} 是相关子查询，走外层行值。</p>
   *
   * <p>{@code AND deleted = 0} 带在 WHERE 上而不是带在子查询里：话题被逻辑删除之后
   * 这行不再有读它的界面（详情页 404、话题墙过滤），继续刷它只是白付一次写。
   * 关注数本身的口径是「不管话题审核状态，有多少人关注就是多少」，
   * 所以子查询里<b>不</b>判 topic.audit_status —— 待审话题走不进这个方法（Service 先挡）。</p>
   */
  @Update("UPDATE topic SET follow_cnt = "
      + "(SELECT COUNT(*) FROM topic_follow WHERE topic_id = topic.id) "
      + "WHERE id = #{topicId} AND deleted = 0")
  int refreshFollowCnt(@Param("topicId") long topicId);
}
