package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.Topic;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

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

  /**
   * 话题下帖子数 +1（任务 3.3 发布成功后调）。
   *
   * <p>必须走 SQL 原子自增，不能「读出来加一再写回去」：两个用户同时往同一话题发帖时，
   * 后者会把前者的计数覆盖掉。写在这里而不是 PostService 里，是为了让计数与表同源。
   */
  @Update("UPDATE topic SET post_cnt = post_cnt + 1 WHERE id = #{topicId} AND deleted = 0")
  int increasePostCnt(long topicId);
}
