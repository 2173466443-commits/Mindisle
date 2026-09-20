package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.AlertTicket;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

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
}
