package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.admin.dto.StatusCountRow;
import com.mindisle.entity.AuditRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审核留痕 Mapper（任务 T6.1 · 需求 §12 规范 5「处置可追溯」）。
 *
 * <p>{@code audit_record} 是<b>只增不改</b>的表：一次机审、一次人审各留一行，
 * 本类因此没有任何 {@code @Update}。要改结论的做法是再裁一次留一行新的，
 * 而不是覆盖旧行 —— 覆盖等于把「当时看到的是 L2」从历史里抹掉，
 * 而 §10 的误报率分析要的正是那份历史。</p>
 */
@Mapper
public interface AuditRecordMapper extends BaseMapper<AuditRecord> {

  /** 某个目标的全部处置轨迹，机审在前、人审在后由 id 升序天然保证。 */
  default List<AuditRecord> listByTarget(String targetType, long targetId) {
    return selectList(new LambdaQueryWrapper<AuditRecord>()
        .eq(AuditRecord::getTargetType, targetType)
        .eq(AuditRecord::getTargetId, targetId)
        .orderByAsc(AuditRecord::getId));
  }

  /** 按 decision 分组计数（A2 大屏「今日机审/人审结论分布」）。 */
  @Select("SELECT decision AS status, COUNT(*) AS cnt FROM audit_record "
      + "WHERE created_at >= #{from} AND deleted = 0 GROUP BY decision")
  List<StatusCountRow> countGroupByDecision(@Param("from") LocalDateTime from);

  /**
   * 人审裁决数（A3 工作台「今天办了多少」与 A4 的人均口径）。
   *
   * <p>{@code channel = 'human'} 而不是 {@code operator_id IS NOT NULL}：将来若有
   * 别的通道带上操作人（比如导入历史数据），按人算的口径会把它们误当成管理员产出。</p>
   */
  @Select("SELECT COUNT(*) FROM audit_record WHERE channel = 'human' "
      + "AND created_at >= #{from} AND deleted = 0")
  long countHumanDecisions(@Param("from") LocalDateTime from);
}