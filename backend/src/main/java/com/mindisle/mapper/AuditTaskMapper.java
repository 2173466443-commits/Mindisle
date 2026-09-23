package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.AuditTask;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
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
}
