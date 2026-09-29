package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.admin.dto.ClosedRow;
import com.mindisle.admin.dto.OverdueRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.entity.AlertTicket;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 危机工单 Mapper（任务 3.3 建单 · 任务 6.4 处置队列复用）。
 *
 * <p>需求 §7.2 对 alert_ticket 的索引要求是 status+level，本类的待认领查询正好走它。</p>
 */
@Mapper
public interface AlertTicketMapper extends BaseMapper<AlertTicket> {

  /** 未认领的工单，按级别与时限排序：L3 永远排在 L2 前面（需求 §5.2 非对称代价）。 */
  default List<AlertTicket> listPending(int limit) {
    return selectList(new LambdaQueryWrapper<AlertTicket>()
        .eq(AlertTicket::getStatus, "pending")
        .orderByDesc(AlertTicket::getLevel)
        .orderByAsc(AlertTicket::getSlaAt)
        .last("limit " + Math.max(1, Math.min(limit, 100))));
  }


  /**
   * 工单队列分页（任务 T6.1-b · 手册 §9.2 A5 · 需求 FR5.6 危机转介闭环）。
   *
   * <p>排序与审核队列同构：先 L3、再按 SLA 升序、无 SLA 的排最后。
   * {@code status} 传空表示「未办结三态一起看」（A5 默认视图就是这个），
   * 而不是「全部」——把已闭环的工单混进默认视图，管理员第一眼看到的会是
   * 一堆早就办完的单子，超时的那几条反而被挤到第二页。</p>
   */
  @Select("<script>"
      + "SELECT * FROM alert_ticket WHERE deleted = 0 "
      + "<choose>"
      + "  <when test=\"status != null and status != ''\"> AND status = #{status} </when>"
      + "  <otherwise> AND status IN ('pending','claimed','doing') </otherwise>"
      + "</choose>"
      + "<if test=\"level != null and level != ''\"> AND level = #{level} </if>"
      + "<if test=\"assigneeId != null\"> AND assignee_id = #{assigneeId} </if>"
      + "<if test=\"userId != null\"> AND user_id = #{userId} </if>"
      + "<if test=\"overdueOnly == true\"> AND sla_at IS NOT NULL AND sla_at &lt; #{now} "
      + "  AND status IN ('pending','claimed','doing') </if>"
      + "ORDER BY FIELD(level,'L3','L2'), (sla_at IS NULL), sla_at, id DESC "
      + "LIMIT #{size} OFFSET #{offset}"
      + "</script>")
  List<AlertTicket> pageTickets(@Param("status") String status, @Param("level") String level,
      @Param("assigneeId") Long assigneeId, @Param("userId") Long userId,
      @Param("overdueOnly") boolean overdueOnly, @Param("now") LocalDateTime now,
      @Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #pageTickets} 同一套 WHERE（含「空 status = 未办结三态」这条默认口径）。 */
  @Select("<script>"
      + "SELECT COUNT(*) FROM alert_ticket WHERE deleted = 0 "
      + "<choose>"
      + "  <when test=\"status != null and status != ''\"> AND status = #{status} </when>"
      + "  <otherwise> AND status IN ('pending','claimed','doing') </otherwise>"
      + "</choose>"
      + "<if test=\"level != null and level != ''\"> AND level = #{level} </if>"
      + "<if test=\"assigneeId != null\"> AND assignee_id = #{assigneeId} </if>"
      + "<if test=\"userId != null\"> AND user_id = #{userId} </if>"
      + "<if test=\"overdueOnly == true\"> AND sla_at IS NOT NULL AND sla_at &lt; #{now} "
      + "  AND status IN ('pending','claimed','doing') </if>"
      + "</script>")
  long countTickets(@Param("status") String status, @Param("level") String level,
      @Param("assigneeId") Long assigneeId, @Param("userId") Long userId,
      @Param("overdueOnly") boolean overdueOnly, @Param("now") LocalDateTime now);

  /**
   * 认领工单：只有 pending 能被认领，返回 0 就是「被别人抢先了」。
   *
   * <p>与审核队列不同，这里<b>不限制</b>认领人必须等于某个人：工单可以转给辅导员，
   * 「谁认领的」由 assignee_id 记，「谁转的」由 admin_op_log 记（BR10）。</p>
   */
  @Update("UPDATE alert_ticket SET status = 'claimed', assignee_id = #{assigneeId}, "
      + "claim_at = #{now}, updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'pending' AND deleted = 0")
  int claim(@Param("id") long id, @Param("assigneeId") long assigneeId,
      @Param("now") LocalDateTime now);

  /** claimed → doing：真正开始处置（打电话、联系辅导员），时间戳用于算认领时效与处置时效。 */
  @Update("UPDATE alert_ticket SET status = 'doing', updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'claimed' AND deleted = 0")
  int startDoing(@Param("id") long id, @Param("now") LocalDateTime now);

  /**
   * 闭环：未办结三态 → closed / false_positive，写处置记录与回访时间。
   *
   * <p>允许在<b>没认领</b>的情况下直接闭环（管理员在大屏上看到 L3 超时单，第一个动作
   * 往往是「这是误报」而不是「先认领再标误报」），此时把 {@code assignee_id} 补成操作人，
   * 用 {@code COALESCE} 而不是覆盖：已经有人认领的单子不能因为别人点了闭环就把归属改掉。</p>
   *
   * <p>{@code toStatus} 由服务层从白名单里取（只允许 closed/false_positive/expired），
   * 不在 SQL 里判合法性——ENUM 写错值在 MySQL 里是静默降级（见 {@link AlertTicket} 类注释），
   * 必须在 Java 侧挡住。</p>
   */
  @Update("UPDATE alert_ticket SET status = #{toStatus}, "
      + "assignee_id = COALESCE(assignee_id, #{operatorId}), "
      + "claim_at = COALESCE(claim_at, #{now}), close_at = #{now}, "
      + "handle_note = #{note}, followup_at = #{followupAt}, updated_at = #{now} "
      + "WHERE id = #{id} AND status IN ('pending','claimed','doing') AND deleted = 0")
  int close(@Param("id") long id, @Param("operatorId") long operatorId,
      @Param("toStatus") String toStatus, @Param("note") String note,
      @Param("followupAt") LocalDateTime followupAt, @Param("now") LocalDateTime now);

  /** 按状态分组计数（A2 大屏「工单六格」与 A5 顶部徽标共用一次查询）。 */
  @Select("SELECT status, COUNT(*) AS cnt FROM alert_ticket WHERE deleted = 0 GROUP BY status")
  List<StatusCountRow> countGroupByStatus();

  /**
   * 超时未办结的条数与最老一条的超时分钟数（大屏红色横幅用）。
   *
   * <p>两件事一条 SQL 出：分开查会得到两个不同时刻的读数，横幅上就会出现
   * 「有 3 条超时」但「最老一条超时 0 分钟」这种自相矛盾的话。</p>
   */
  @Select("SELECT COUNT(*) AS cnt, "
      + "CAST(COALESCE(MAX(TIMESTAMPDIFF(MINUTE, sla_at, #{now})), 0) AS SIGNED) AS worst_minutes "
      + "FROM alert_ticket WHERE deleted = 0 AND sla_at IS NOT NULL AND sla_at < #{now} "
      + "AND status IN ('pending','claimed','doing')")
  OverdueRow overdueStats(@Param("now") LocalDateTime now);

  /**
   * 单个用户的工单时间线（A5 右侧「这个人经历过什么」，手册 §9.2 要求证据片段可回看）。
   *
   * <p>不带 status 过滤：误报与已闭环同样是「这个人被系统判过危机」的事实，
   * 只给未办结的那几条会让辅导员以为这是第一次。</p>
   */
  @Select("SELECT * FROM alert_ticket WHERE user_id = #{userId} AND deleted = 0 "
      + "ORDER BY id DESC LIMIT #{limit}")
  List<AlertTicket> timelineByUser(@Param("userId") long userId, @Param("limit") int limit);

  /** 大屏「今日新增工单」与「闭环率」用：一个时间窗内的总数与已闭环数。 */
  @Select("SELECT COUNT(*) AS cnt, "
      + "SUM(CASE WHEN status IN ('closed','false_positive') THEN 1 ELSE 0 END) AS closed_cnt "
      + "FROM alert_ticket WHERE deleted = 0 AND created_at >= #{from}")
  ClosedRow closedStatsSince(@Param("from") LocalDateTime from);
}
