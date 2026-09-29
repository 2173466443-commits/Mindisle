package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostAppeal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 申诉 Mapper（任务 T6.4）。
 *
 * <p>两条自定义 SQL 各自解决一个 Wrapper 表达不出来的问题：
 * {@link #adjudicate} 的「只有还没裁定的行能被裁定」要靠 WHERE 里的 status 当场回答，
 * 放在 Java 里先查后判就有读改写窗口；{@link #countFinalByPost} 数的是历史终态行，
 * 用来挡住「输了再换个说法再来」。</p>
 */
@Mapper
public interface PostAppealMapper extends BaseMapper<PostAppeal> {

  /** 这条帖子有没有还没裁定的申诉（唯一键冲突前的友好提示用）。 */
  default PostAppeal findPending(long postId) {
    return selectOne(new LambdaQueryWrapper<PostAppeal>()
        .eq(PostAppeal::getPostId, postId)
        .eq(PostAppeal::getStatus, PostAppeal.STATUS_PENDING)
        .orderByDesc(PostAppeal::getId)
        .last("limit 1"));
  }

  /** 这条帖子的申诉史，id 倒序（二级队列详情页用）。 */
  default List<PostAppeal> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostAppeal>()
        .eq(PostAppeal::getPostId, postId)
        .orderByDesc(PostAppeal::getId));
  }

  /** 已经裁完的次数：>=1 就是「终态不可再诉」。 */
  @Select("SELECT COUNT(*) FROM post_appeal WHERE post_id = #{postId} "
      + "AND status IN ('ACCEPTED','REJECTED') AND deleted = 0")
  long countFinalByPost(@Param("postId") long postId);

  /** 队列分页：PENDING 在前（同状态内新单在前），裁定完的沉底。 */
  @Select("<script>SELECT * FROM post_appeal WHERE deleted = 0 "
      + "<if test=\"status != null and status != ''\"> AND status = #{status} </if>"
      + "ORDER BY (status &lt;&gt; 'PENDING'), id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<PostAppeal> pageAppeals(@Param("status") String status,
      @Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #pageAppeals} 同一套 WHERE。 */
  @Select("<script>SELECT COUNT(*) FROM post_appeal WHERE deleted = 0 "
      + "<if test=\"status != null and status != ''\"> AND status = #{status} </if>"
      + "</script>")
  long countAppeals(@Param("status") String status);

  /**
   * 裁定：只有 PENDING 行能改成终态，返回 0 表示已经被别人办掉了。
   *
   * <p>{@code handler_id} 只在本次写入：二级队列不允许「A 认领 B 裁定」的错位留痕，
   * 谁点下按钮谁就是裁定人，这与审核队列的认领语义刻意不同（审核要的是并发抢占，
   * 申诉要的是责任唯一）。</p>
   */
  @Update("UPDATE post_appeal SET status = #{toStatus}, handler_id = #{handlerId}, "
      + "handled_at = #{now}, result_note = #{note}, updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'PENDING' AND deleted = 0")
  int adjudicate(@Param("id") long id, @Param("handlerId") long handlerId,
      @Param("toStatus") String toStatus, @Param("note") String note,
      @Param("now") LocalDateTime now);
}