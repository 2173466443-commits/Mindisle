package com.mindisle.mapper;

import java.time.LocalDate;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.WeeklyReport;

/**
 * 情绪周报 Mapper（任务 T4.20）。
 *
 * <p>{@link #upsert} 走 {@code INSERT ... ON DUPLICATE KEY UPDATE}，靠
 * {@code uk_user_week(user_id, week_start)} 保证「同一个周一只有一份周报」。
 * 为什么不用「先 SELECT 再 insert/update」两步：定时任务与用户手动点「再算一次」
 * 会同时打进来，两步之间有窗口，最后一个 INSERT 会撞 1062 变成一个 90004。
 * 一条语句让 MySQL 自己去处理竞争，是这里最短的正确路径。</p>
 */
@Mapper
public interface WeeklyReportMapper extends BaseMapper<WeeklyReport> {

  /** 生成/重算周报的落库口。返回值 1=新插入、2=命中唯一键后更新（MySQL 的语义）。 */
  @Insert("INSERT INTO weekly_report (user_id, week_start, week_end, checkin_days, record_cnt, "
      + "dominant_label, avg_intensity, positive_ratio, trend_delta, insight, summary_text, "
      + "generator, shared_flag) VALUES (#{userId}, #{weekStart}, #{weekEnd}, #{checkinDays}, "
      + "#{recordCnt}, #{dominantLabel}, #{avgIntensity}, #{positiveRatio}, #{trendDelta}, "
      + "#{insight}, #{summaryText}, #{generator}, 0) "
      + "ON DUPLICATE KEY UPDATE week_end = VALUES(week_end), checkin_days = VALUES(checkin_days), "
      + "record_cnt = VALUES(record_cnt), dominant_label = VALUES(dominant_label), "
      + "avg_intensity = VALUES(avg_intensity), positive_ratio = VALUES(positive_ratio), "
      + "trend_delta = VALUES(trend_delta), insight = VALUES(insight), "
      + "summary_text = VALUES(summary_text), generator = VALUES(generator)")
  int upsert(@Param("userId") long userId, @Param("weekStart") LocalDate weekStart,
      @Param("weekEnd") LocalDate weekEnd, @Param("checkinDays") int checkinDays,
      @Param("recordCnt") int recordCnt, @Param("dominantLabel") String dominantLabel,
      @Param("avgIntensity") java.math.BigDecimal avgIntensity,
      @Param("positiveRatio") java.math.BigDecimal positiveRatio,
      @Param("trendDelta") java.math.BigDecimal trendDelta,
      @Param("insight") String insight, @Param("summaryText") String summaryText,
      @Param("generator") String generator);

  @Select("SELECT * FROM weekly_report WHERE user_id = #{userId} AND week_start = #{weekStart} "
      + "AND deleted = 0 LIMIT 1")
  WeeklyReport findByUserWeek(@Param("userId") long userId, @Param("weekStart") LocalDate weekStart);

  /**
   * 按主键读一行，<b>只读没被逻辑删除的</b>（任务 T4.20 ③ 的去标识分享用）。
   *
   * <p>为什么不直接用 {@code BaseMapper#selectById}：{@code @TableLogic} 的 {@code deleted = 0}
   * 只加在 MP 自己生成的语句里，看起来 {@code selectById} 也带，但那层「自动」意味着
   * 一旦哪天有人把实体的 {@code @TableLogic} 摘掉，这里就会安静地开始能分享已删除的周报。
   * 一条显式 SQL 把这句话说在明面上：分享这条路径不接受已删除的周报。</p>
   */
  @Select("SELECT * FROM weekly_report WHERE id = #{reportId} AND deleted = 0 LIMIT 1")
  WeeklyReport findById(@Param("reportId") long reportId);

  /**
   * 标记这份周报已分享并回填帖子 id（任务 T4.20 ③）。
   *
   * <p>{@code shared_flag} 在这里第一次有了唯一的写入通道 —— 之前它是只读的：
   * {@link #upsert} 的 INSERT 里硬编码 0，{@code ON DUPLICATE KEY UPDATE} 又刻意不更新它，
   * 这两处都是对的（重算一份周报不该把「已经分享过」这件事抹掉），
   * 但结果是这个字段在全仓库没有任何一处会被置成 1。这条语句就是补上的那个缺口。</p>
   *
   * <p>{@code WHERE ... AND deleted = 0} 与返回值一起构成第二道越权/竞态闸：
   * 调用方（{@code WeeklyReportShareService}）已经判过作者与存在性，
   * 这里再判一次是因为「判」与「写」之间隔着一次发帖（可能几百毫秒），
   * 期间用户完全可能在隐私中心把这份周报删掉。返回 0 就说明这一行没写成，
   * 服务层要把它当成失败而不是成功。</p>
   *
   * @return 命中并更新的行数，正常恒为 1；0 = 这一行不存在或已被删除
   */
  @Update("UPDATE weekly_report SET shared_flag = 1, shared_post_id = #{postId} "
      + "WHERE id = #{reportId} AND deleted = 0")
  int markShared(@Param("reportId") long reportId, @Param("postId") Long postId);
}
