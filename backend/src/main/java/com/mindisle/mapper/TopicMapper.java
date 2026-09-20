package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Topic;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 话题 Mapper（手册 §5.2 T2.5）。 */
@Mapper
public interface TopicMapper extends BaseMapper<Topic> {

  /**
   * 公开话题墙：官方 且 已过审，按热度倒序（需求 FR1.7 / §6.2 热度排序）。
   *
   * <p>这是阶段 2 里唯一「真实跑通业务语义」的内容查询，Gate 2 的 20 个接口靠它凑够内容域计数。
   */
  default List<Topic> listOfficialApproved(int limit) {
    return selectList(new LambdaQueryWrapper<Topic>()
        .eq(Topic::getIsOfficial, 1)
        .eq(Topic::getAuditStatus, "APPROVED")
        .orderByDesc(Topic::getHotScore)
        .orderByAsc(Topic::getId)
        .last("limit " + Math.max(1, Math.min(limit, 50))));
  }
}
