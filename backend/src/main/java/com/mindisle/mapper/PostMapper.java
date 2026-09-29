package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Post;
import java.time.LocalDateTime;
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
   * 评论数重算（任务 3.7 · 手册 §6.1 行 3.7）。
   *
   * <p>口径与 {@link #refreshLikeCnt} 完全一致：每次写之后把冗余列刷成真相表的重算值，
   * <b>漂移在结构上不可能发生</b>，而不是「+1 再定期回写」。两点差别要写清：
   * ① 这里数的是行而不是人（{@code COUNT(*)}），因为评论没有「一人一条」的唯一键，
   * 同一个人可以合法地发十楼；② 只数 {@code PUBLISHED}，待审评论仅作者可见，
   * 计进卡片数字就会出现「卡片写 3 条、点进去只有 2 条」——同一取舍见
   * {@link com.mindisle.mapper.CommentMapper#countPublished}。</p>
   */
  @Update("UPDATE post SET comment_cnt = "
      + "(SELECT COUNT(*) FROM comment WHERE post_id = post.id "
      + "AND status = 'PUBLISHED' AND deleted = 0) WHERE id = #{id}")
  int refreshCommentCnt(@Param("id") long id);

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
   * 被举报次数重算（任务 T3.11 · 需求 FR4.4、FR4.7）。
   *
   * <p>与 {@link #refreshCommentCnt}、{@link #refreshLikeCnt} 同一教义：<b>按真相表重算覆盖写</b>，
   * 不做 {@code report_cnt = report_cnt + 1}。差别只在数的对象——这里数的是「多少个人举报过」
   * （{@code COUNT(DISTINCT reporter_id)}），因为 {@code uk_reporter_target} 决定一个人对一条内容
   * 只有一次表达机会，重复点击不叠加权重（BR2 幂等）。这一列将来还要喂给 T3.10 的行为埋点
   * （举报 = -5，需求 §8.2.1），到那时它必须仍然等于「几个人说过」，否则质量分就跟着重复点击漂。</p>
   *
   * <p>只数 {@code target_type='post'}：评论举报算评论自己的账，不计进帖子的阈值——
   * 否则一个人可以在自己帖下自导自演十条评论、再举报十条，把一条正常帖子打进人审队列。</p>
   */
  @Update("UPDATE post SET report_cnt = "
      + "(SELECT COUNT(DISTINCT reporter_id) FROM content_report WHERE target_type = 'post' "
      + "AND target_id = post.id AND deleted = 0) WHERE id = #{id}")
  int refreshReportCnt(@Param("id") long id);

  /**
   * 状态比较改写（任务 T3.11 举报转人审 · BR10 状态机）。
   *
   * <p><b>为什么不用 {@code updateById(post)}</b>：手里那份 Post 是这次请求开始时读的快照，
   * 拿它整体回写会把窗口期内作者本人的编辑、别的通道的处置一起覆盖掉——「举报刚好把
   * 管理员刚下架的帖子又改回可见」这种事只有在并发下才露头。带 {@code AND status = #{fromStatus}}
   * 之后，状态一旦被别人改走就是 0 行，调用方据此安静地什么都不做。</p>
   *
   * <p>这里<b>不</b>顺带写 published_at 之类的字段：转入人审不是发布，
   * 而流转留痕由 {@code post_status_log} 负责（{@code ReportService} 只在影响行数 = 1 时写日志，
   * 于是「日志条数 = 真实流转次数」这条不变式仍然成立）。</p>
   */
  @Update("UPDATE post SET status = #{toStatus} WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
  int compareAndSetStatus(@Param("id") long id, @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus);

  /**
   * 人审放行时回填发布时间（任务 T6.6 · 手册 §9.4 D4「通过后即时可见」）。
   *
   * <p><b>为什么要单独开一个口子</b>：{@link #compareAndSetStatus} 刻意不碰 published_at
   * （转入人审不是发布）。于是灰词帖走「机审拦下 → 人工通过」这条路进来时，帖子状态已经是
   * PUBLISHED 却带着 published_at = NULL，而下游三个读取方都按发布时间说话：
   * 广场游标 {@code order by published_at desc, id desc} 在 MySQL DESC 语义下把 NULL 排在最后；
   * {@code RecommendMapper} 的「近 N 天新帖」用 {@code published_at >= #{since}} 比较，NULL 恒不成立；
   * 热池排序 {@code quality_score DESC, published_at DESC} 同样把它压到末尾。
   * 结果是「状态机说它上线了，读取侧却当它没发布」——Gate6 D4 的「即时可见」只走完了一半。
   * 这里补的就是「人审放行那一刻才是这条帖子的发布时刻」这条语义。</p>
   *
   * <p>{@code published_at IS NULL} 是幂等闸门：图片抽审那条分支进来的帖子本来就是 PUBLISHED
   * 且早就有发布时间，重复裁决也不会把发布时间挪到第二次点击上；没回填就返回 0 行，
   * 调用方据此在审计 detail 里写「回填发布时间=false」，不留「看起来做了其实没做」的模糊地带。</p>
   */
  @Update("UPDATE post SET published_at = #{publishedAt} "
      + "WHERE id = #{id} AND status = 'PUBLISHED' AND published_at IS NULL AND deleted = 0")
  int stampPublishedAt(@Param("id") long id, @Param("publishedAt") LocalDateTime publishedAt);

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
