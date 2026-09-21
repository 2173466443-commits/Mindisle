package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostTopic;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 帖-话题关联 Mapper（任务 3.3、3.8）。 */
@Mapper
public interface PostTopicMapper extends BaseMapper<PostTopic> {

  /**
   * 一屏帖子的话题关联批量取（任务 3.5），与 {@code PostImageMapper#listByPosts} 同一动机：
   * 列表接口不能逐帖查，见 NFR3 列表 P95 300ms。
   *
   * <p>按 id 升序就是发帖时的关联顺序（post_topic 无 sort 列，插入顺序即用户勾选顺序），
   * 话题先后对用户是有含义的，第一个通常是主话题，所以不能按 topic_id 排。</p>
   */
  default List<PostTopic> listByPosts(List<Long> postIds) {
    if (postIds == null || postIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<PostTopic>()
        .in(PostTopic::getPostId, postIds)
        .orderByAsc(PostTopic::getId));
  }

  /** 某帖挂的话题 id 列表，按关联顺序。 */
  default List<PostTopic> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getPostId, postId));
  }
}
