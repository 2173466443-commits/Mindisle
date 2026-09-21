package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 帖子 Mapper（任务 3.3 · 手册 §5.2）。
 *
 * <p>与 {@code UserMapper} 一样不写 XML：单表条件查询用 LambdaQueryWrapper 足够。
 * 目前只有楼层号一条裸 SQL，因为聚合函数在 Wrapper 里表达反而更绕。</p>
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

  /**
   * 取下一个树洞楼层号（手册 §5.1 v1.1.2 补列「全局连续递增」）。
   *
   * <p><b>已知妥协，写白不藏</b>：MAX+1 是「读—算—写」三步，并发下两帖可能拿到同一楼层号。
   * {@code floor_no} 上没有唯一索引（DDL 有意如此：楼层是展示辅助，不是标识符，
   * post.id 才是），所以最坏结果是两个树洞同号，不会报错也不会丢帖。
   * 真要严格连续得引序列或 SELECT FOR UPDATE 锁全表，代价与收益不匹配。
   * 与任务 3.12 配额「peek-then-incr 最多多放 1 帖」是同一类口径，一并写进答辩局限。</p>
   */
  @Select("SELECT COALESCE(MAX(floor_no), 0) + 1 FROM post WHERE type = 'hole'")
  Integer nextHoleFloor();

  /**
   * 浏览量批量回写（任务 3.5 · 手册 §6.1 3.5 行）。
   *
   * <p><b>为什么是 {@code view_cnt = view_cnt + n} 而不是 {@code set view_cnt = ?}</b>：
   * 前者是数据库内的原子累加，多个实例同时回写也不会丢计数；后者是「读—算—写」，
   * 两个实例各拿到旧值再回写，就会把对方那一笔覆盖掉。<br>
   * <b>WHERE 不带 status</b>：即使帖子在这个窗口里被下架，浏览量也是已经发生的事实，
   * 没理由丢掉；但 deleted=0 要带，否则注销清理后的残留增量会写进一个永远不再读的行。</p>
   */
  @Update("UPDATE post SET view_cnt = view_cnt + #{delta} WHERE id = #{id} AND deleted = 0")
  int increaseViewCnt(long id, long delta);

  /**
   * 用真相表重算点赞/收藏数（任务 3.6 · 需求 BR2）。
   *
   * <p><b>为什么是「重算」而不是「INCR + 5 分钟回写」</b>：BR2 那半句是给浏览量写的
   * （浏览是全站写得最频的动作，见 {@link com.mindisle.post.ViewCountService}）。点赞收藏的频率差两个量级，
   * 而缓存计数器一旦崩溃重启就会和 post_like 永久对不上——「计数与真相不一致」这种问题
   * 在答辩现场只要被问一次就说不清。重算是一条语句内的 {@code COUNT(DISTINCT user_id)} 子查询，
   * 写路径上把列刷成真相，<b>漂移在结构上不可能发生</b>；代价是每次互动多一次带 idx_target 前缀的
   * 计数扫描，单目标几百行了无压力，真到十万赞量级再换成增量计数器（已写进手册 §14 的边界条目）。</p>
   *
   * <p>子查询读的是 post_like、更新的是 post，两张不同的表，不触发
   * {@code ER_UPDATE_TABLE_USED}；{@code target_id = post.id} 是相关子查询，走外层行值。</p>
   */
  @Update("UPDATE post SET like_cnt = "
      + "(SELECT COUNT(DISTINCT user_id) FROM post_like WHERE target_type = 'post' "
      + "AND target_id = post.id AND action_type = 'LIKE' AND deleted = 0) WHERE id = #{id}")
  int refreshLikeCnt(@Param("id") long id);

  /** 收藏数重算，口径与 {@link #refreshLikeCnt} 完全一致，只是 action_type 换成 COLLECT。 */
  @Update("UPDATE post SET collect_cnt = "
      + "(SELECT COUNT(DISTINCT user_id) FROM post_like WHERE target_type = 'post' "
      + "AND target_id = post.id AND action_type = 'COLLECT' AND deleted = 0) WHERE id = #{id}")
  int refreshCollectCnt(@Param("id") long id);

  /**
   * 主页「获赞数」（任务 3.6 · 需求 FR1.5）。
   *
   * <p>只统计<b>公开且非匿名</b>的已发布帖：主页上的「他收到过多少赞」如果把自己的树洞帖
   * 也折进来，就等于向访问者承认「这个账号还有若干匿名帖」，那是需求 FR1.4 明确不许普通用户
   * 做到的一件事（哪怕只是一个数字）。与 {@code UserPostController} 公开主页列表的三重收窄同一口径。</p>
   */
  @Select("SELECT COALESCE(SUM(like_cnt), 0) FROM post "
      + "WHERE user_id = #{userId} AND status = 'PUBLISHED' AND deleted = 0 "
      + "AND visibility = 'public' AND is_anonymous = 0 AND alias_id IS NULL")
  long sumReceivedLikes(@Param("userId") long userId);

  /** 主页「公开帖数」，过滤条件与 {@link #sumReceivedLikes} 逐字相同，否则两个数字会互相打脸。 */
  @Select("SELECT COUNT(*) FROM post "
      + "WHERE user_id = #{userId} AND status = 'PUBLISHED' AND deleted = 0 "
      + "AND visibility = 'public' AND is_anonymous = 0 AND alias_id IS NULL")
  long countPublicPosts(@Param("userId") long userId);
}
