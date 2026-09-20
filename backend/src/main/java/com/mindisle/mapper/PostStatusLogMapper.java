package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostStatusLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 状态流转留痕 Mapper（任务 3.3 · BR10）。 */
@Mapper
public interface PostStatusLogMapper extends BaseMapper<PostStatusLog> {

  /** 某帖的完整流转链，按发生顺序；管理端详情页与 Gate6 举证都读它。 */
  default List<PostStatusLog> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostStatusLog>()
        .eq(PostStatusLog::getPostId, postId)
        .orderByAsc(PostStatusLog::getId));
  }
}
