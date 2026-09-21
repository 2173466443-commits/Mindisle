package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 评论 Mapper（任务 3.7 · 需求 FR4.4）。
 *
 * <p>读写主干走 BaseMapper + LambdaQueryWrapper：{@code Comment} 上挂了
 * {@code @TableLogic}，列表查询会自动带上 {@code deleted = 0}。裸 SQL 只留一条
 * {@link #countPublished}，因为 post.comment_cnt 的重算子查询要带状态与逻辑删除两个条件，
 * 写在 Wrapper 里反而要把聚合塞进 select 字符串。</p>
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

  /**
   * 某帖的「已发布且未删除」评论条数（楼中楼一起算，与 post.comment_cnt 同口径）。
   *
   * <p><b>为什么把 PENDING 排除在计数之外</b>：{@code post.comment_cnt} 是展示在卡片上的数字，
   * 而待审评论只有作者本人看得到（与帖子的「作者额外可见待审」同一口径）。
   * 把 PENDING 计进去就会出现「卡片写 3 条、点进去只有 2 条」——
   * 用户会以为是 bug，而真实原因（有一条还在审核）恰恰是产品不想公开的信息。</p>
   */
  @Select("SELECT COUNT(*) FROM comment "
      + "WHERE post_id = #{postId} AND status = 'PUBLISHED' AND deleted = 0")
  long countPublished(@Param("postId") long postId);
}
