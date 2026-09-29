package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.admin.dto.AssigneeStatRow;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.entity.AuditTask;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审核任务 Mapper（任务 T3.11 · 需求 FR4.7「举报即刻生成 audit_task」）。
 *
 * <p>本类只提供「取那条唯一的待办」「幂等插入」「升优先级」三个能力，
 * 处置（认领、通过、驳回、升级危机工单）属任务 6.1，届时再往这里加语句，
 * 不在举报链路里顺手改状态。</p>
 */
@Mapper
public interface AuditTaskMapper extends BaseMapper<AuditTask> {

  /**
   * 某目标当前的待办任务；DDL 的 {@code uk_target_pending} 保证最多一条。
   *
   * <p>{@code limit 1} 是保险，不是语义：万一历史数据里真有两行 PENDING（别的通道写的），
   * {@code selectOne} 会抛 TooManyResultsException 把一次举报打成 500。
   * 取 id 最大的那条才是对的方向——新的那行才代表当时的规则版本。</p>
   */
  default AuditTask findPending(String targetType, long targetId) {
    return selectOne(new LambdaQueryWrapper<AuditTask>()
        .eq(AuditTask::getTargetType, targetType)
        .eq(AuditTask::getTargetId, targetId)
        .eq(AuditTask::getStatus, AuditTask.STATUS_PENDING)
        .orderByDesc(AuditTask::getId)
        .last("limit 1"));
  }

  /**
   * 幂等插入（撞 {@code uk_target_pending} 返回 0），理由与
   * {@link ContentReportMapper#insertIgnore} 完全相同：并发双报不该把任何一个人的举报滚掉。
   *
   * <p><b>返回 0 时不要相信回填的自增 id</b>：MySQL 照样消耗一个自增值，驱动也可能把它写回实体。
   * 调用方在 0 行时必须改用 {@link #findPending} 重新定位那一条。</p>
   */
  @Insert("INSERT IGNORE INTO audit_task "
      + "(target_type, target_id, source, channel, result, risk_score, risk_level, status, sla_at, remark) "
      + "VALUES (#{targetType}, #{targetId}, #{source}, #{channel}, #{result}, #{riskScore}, "
      + "#{riskLevel}, #{status}, #{slaAt}, #{remark})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertIgnore(AuditTask task);

  /**
   * 升优先级：只改风险三件套与时间戳，<b>不改 status、不改 source、不改 remark</b>。
   *
   * <p>remark 写的是「第一份复扫当时看到了什么」，覆盖它等于把留痕改成事后编的。
   * 「新等级是否高于旧等级」这条比较放在 Java 侧（{@code ReportService#rankOf}），
   * SQL 里比 ENUM 字面量顺序是另一套语义，两边各判一半迟早分叉。</p>
   */
  @Update("UPDATE audit_task SET risk_level = #{riskLevel}, risk_score = #{riskScore}, "
      + "sla_at = #{slaAt}, updated_at = #{updatedAt} WHERE id = #{id}")
  int escalate(@Param("id") long id, @Param("riskLevel") String riskLevel,
      @Param("riskScore") BigDecimal riskScore, @Param("slaAt") LocalDateTime slaAt,
      @Param("updatedAt") LocalDateTime updatedAt);


  /**
   * 审核队列分页（任务 T6.1 · 手册 §9.2 A4 · 需求 FR7.3）。
   *
   * <p><b>排序口径是这条 SQL 的全部意义</b>：先按风险等级（L3 永远在 L1 前面），
   * 再按 SLA 到期时间升序，<b>没有 sla_at 的排最后</b>（{@code sla_at IS NULL} 在 MySQL 里
   * 布尔值 0/1，当排序键用就是「有 SLA 的在前」），最后才按 id 倒序。
   * 如果只按 id 倒序，一个 4 小时没人办的 L3 会被 5 分钟前的 L1 顶到第二页——
   * 那是需求 §5.2 处置矩阵最不能接受的一种错。</p>
   *
   * <p>{@code SELECT *} 配 {@code AuditTask} 靠的是 {@code map-underscore-to-camel-case}
   * （阶段 2 就在 application.yml 里打开了），与 {@code PostMapper} 的写法一致，不另起 resultMap。</p>
   */
  @Select("<script>"
      + "SELECT * FROM audit_task WHERE deleted = 0 "
      + "<if test=\"status != null and status != ''\"> AND status = #{status} </if>"
      + "<if test=\"riskLevel != null and riskLevel != ''\"> AND risk_level = #{riskLevel} </if>"
      + "<if test=\"targetType != null and targetType != ''\"> AND target_type = #{targetType} </if>"
      + "<if test=\"assigneeId != null\"> AND assignee_id = #{assigneeId} </if>"
      + "<if test=\"overdueOnly == true\"> AND sla_at IS NOT NULL AND sla_at &lt; #{now} "
      + "  AND status IN ('PENDING','PROCESSING') </if>"
      + "ORDER BY FIELD(risk_level,'L3','L2','L1','L0'), (sla_at IS NULL), sla_at, id DESC "
      + "LIMIT #{size} OFFSET #{offset}"
      + "</script>")
  List<AuditTask> pageQueue(@Param("status") String status, @Param("riskLevel") String riskLevel,
      @Param("targetType") String targetType, @Param("assigneeId") Long assigneeId,
      @Param("overdueOnly") boolean overdueOnly, @Param("now") LocalDateTime now,
      @Param("offset") long offset, @Param("size") int size);

  /** 与 {@link #pageQueue} 完全同一套 WHERE，否则「总数」会把筛掉的那部分也数进去。 */
  @Select("<script>"
      + "SELECT COUNT(*) FROM audit_task WHERE deleted = 0 "
      + "<if test=\"status != null and status != ''\"> AND status = #{status} </if>"
      + "<if test=\"riskLevel != null and riskLevel != ''\"> AND risk_level = #{riskLevel} </if>"
      + "<if test=\"targetType != null and targetType != ''\"> AND target_type = #{targetType} </if>"
      + "<if test=\"assigneeId != null\"> AND assignee_id = #{assigneeId} </if>"
      + "<if test=\"overdueOnly == true\"> AND sla_at IS NOT NULL AND sla_at &lt; #{now} "
      + "  AND status IN ('PENDING','PROCESSING') </if>"
      + "</script>")
  long countQueue(@Param("status") String status, @Param("riskLevel") String riskLevel,
      @Param("targetType") String targetType, @Param("assigneeId") Long assigneeId,
      @Param("overdueOnly") boolean overdueOnly, @Param("now") LocalDateTime now);

  /**
   * 认领：PENDING → PROCESSING，返回 1 才算抢到。
   *
   * <p><b>并发语义完全靠这条 WHERE</b>：两个审核人同时点「认领」，后到的那一次影响 0 行，
   * 服务层据此回 10003「这条已经被别人接走了」，而不是把 assignee_id 覆盖成最后点的人。
   * 不加 {@code status='PENDING'} 的话，认领会把已办结的任务重新打开——那是更严重的错。</p>
   *
   * <p>⚠️ 已知边界：DDL 的 {@code uk_target_pending(target_type,target_id,status)} 让同一目标
   * 只能有一条 PROCESSING。若上一轮 PROCESSING 还挂着没办结、这一轮又新建了 PENDING，
   * 认领会抛 {@code DuplicateKeyException}；服务层把它翻成 PARAM_INVALID 让人先处置旧任务，
   * 不在这里静默改状态。</p>
   */
  @Update("UPDATE audit_task SET status = 'PROCESSING', assignee_id = #{assigneeId}, "
      + "updated_at = #{now} WHERE id = #{id} AND status = 'PENDING' AND deleted = 0")
  int claim(@Param("id") long id, @Param("assigneeId") long assigneeId,
      @Param("now") LocalDateTime now);

  /**
   * 裁决：PROCESSING → PASSED / REJECTED，且<b>只有认领人自己能改</b>
   * （{@code assignee_id = #{assigneeId}} 在 WHERE 里，不在 Java 里判，避免读改写窗口）。
   *
   * <p>remark 由调用方给整句（含理由），这里不拼接：留痕里的话是谁说的要能追到人。</p>
   */
  @Update("UPDATE audit_task SET status = #{toStatus}, remark = #{remark}, updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'PROCESSING' AND assignee_id = #{assigneeId} AND deleted = 0")
  int adjudicate(@Param("id") long id, @Param("assigneeId") long assigneeId,
      @Param("toStatus") String toStatus, @Param("remark") String remark,
      @Param("now") LocalDateTime now);

  /** 队列总数按状态分组（大屏 A2 的「待审积压」六格，一次查完，不给前端发六个请求）。 */
  @Select("SELECT status, COUNT(*) AS cnt FROM audit_task WHERE deleted = 0 GROUP BY status")
  List<StatusCountRow> countGroupByStatus();

  /**
   * 人均办件量与平均耗时（手册 §9.2 A4 明确要求的两个指标）。
   *
   * <p>{@code CAST(... AS SIGNED)} 不是洁癖：AVG/SUM 在 MySQL 里返回 DECIMAL，
   * 直接塞进 {@code Long} 字段靠的是驱动隐式截断，换成别的驱动就是 ClassCastException。</p>
   */
  @Select("SELECT assignee_id AS assignee_id, COUNT(*) AS cnt, "
      + "CAST(AVG(TIMESTAMPDIFF(SECOND, created_at, updated_at)) AS SIGNED) AS avg_seconds, "
      + "CAST(SUM(CASE WHEN sla_at IS NOT NULL AND updated_at > sla_at THEN 1 ELSE 0 END) AS SIGNED) AS overdue_cnt "
      + "FROM audit_task WHERE deleted = 0 AND status IN ('PASSED','REJECTED') "
      + "AND assignee_id IS NOT NULL GROUP BY assignee_id ORDER BY cnt DESC")
  List<AssigneeStatRow> assigneeStats();

  /**
   * 「这个作者历史上被审过哪些帖子」（A4 右侧面板，手册 §9.2 要求审核时能看到惯犯）。
   *
   * <p>只查 target_type='post'：跨类型 union 需要为 comment/pm/hole 各写一条 join，
   * 而面板要回答的是「这个人在社区里发过什么被抓住过」，私信属于另一条链路（BR10）。</p>
   */
  @Select("SELECT t.* FROM audit_task t JOIN post p ON p.id = t.target_id "
      + "WHERE t.target_type = 'post' AND p.user_id = #{userId} AND t.deleted = 0 "
      + "ORDER BY t.id DESC LIMIT #{limit}")
  List<AuditTask> listPostTasksByAuthor(@Param("userId") long userId, @Param("limit") int limit);

  /**
   * 超时未裁决自动回队（手册 §9.1 第 1 条：领取后 10 分钟不动就退回 PENDING 并清空受理人）。
   *
   * <p>判据用 updated_at：领取与任何写操作都会推它，所以它回答的是「距最后一次动作多久了」。
   * assignee_id 必须置 NULL——留着旧受理人，下一个人认领照样成功（WHERE 只看 status），
   * 但界面会出现「待领取却写着别人的名字」这种自相矛盾的行。</p>
   */
  @Update("UPDATE audit_task SET status = 'PENDING', assignee_id = NULL, updated_at = #{now} "
      + "WHERE status = 'PROCESSING' AND updated_at < #{deadline} AND deleted = 0 "
      + "LIMIT #{cap}")
  int releaseTimedOut(@Param("deadline") LocalDateTime deadline, @Param("now") LocalDateTime now,
      @Param("cap") int cap);
}
