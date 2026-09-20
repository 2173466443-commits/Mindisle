package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostTopic;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 帖-话题关联 Mapper（任务 3.3、3.8）。 */
@Mapper
public interface PostTopicMapper extends BaseMapper<PostTopic> {

  /** 某帖挂的话题 id 列表，按关联顺序。 */
  default List<PostTopic> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getPostId, postId));
  }
}
