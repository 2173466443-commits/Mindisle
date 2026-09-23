package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.ContentReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 内容举报 Mapper（任务 T3.11 · 需求 FR4.7）。
 *
 * <p>只有两条裸 SQL，其余读写走 BaseMapper + LambdaQueryWrapper，取舍与
 * {@link PostLikeMapper} 一致：「撞唯一键就什么都不发生」这件事 Wrapper 表达不出来。</p>
 */
@Mapper
public interface ContentReportMapper extends BaseMapper<ContentReport> {

  /**
   * 幂等插入：撞 {@code uk_reporter_target} 就返回 0 而不是抛错。
   *
   * <p><b>为什么用 INSERT IGNORE 而不是「先查再插」</b>：两个不同账号同时举报同一帖时，
   * 「先查再插」两边都查不到、于是双双插入，第二条在数据库层炸成约束冲突，
   * 顺着 {@code @Transactional} 把<b>那一整条链路</b>（含他的举报行）一起回滚——
   * 用户看到的是「我点了举报，什么都没留下」。这里让影响行数当场回答「这次是不是重复」，
   * 一次往返、无竞态，与 {@link PostLikeMapper#insertIgnore} 同一套路。</p>
   *
   * <p>{@code created_at/updated_at} 不在列清单里，交给 DDL 的 DEFAULT CURRENT_TIMESTAMP(3)：
   * 举报时间是「服务端收到的那一刻」，客户端传什么都不该信。</p>
   */
  @Insert("INSERT IGNORE INTO content_report "
      + "(reporter_id, target_type, target_id, post_id, author_id, reason, description, evidence_urls, status) "
      + "VALUES (#{reporterId}, #{targetType}, #{targetId}, #{postId}, #{authorId}, #{reason}, "
      + "#{description}, #{evidenceUrls}, #{status})")
  int insertIgnore(ContentReport row);

  /**
   * 举报数真相：<b>多少人</b>举报了这条内容。
   *
   * <p>数人而不是数行（与 {@link PostLikeMapper#countActiveUsers} 同一口径）：
   * {@code deleted} 位不在唯一键里，被清理过的历史行理论上仍可能留下多行，
   * 而「阈值转人审」的语义本来就是「几个人说过这条不该在」。</p>
   */
  @Select("SELECT COUNT(DISTINCT reporter_id) FROM content_report "
      + "WHERE target_type = 'post' AND target_id = #{postId} AND deleted = 0")
  long countPostReporters(@Param("postId") long postId);
}
