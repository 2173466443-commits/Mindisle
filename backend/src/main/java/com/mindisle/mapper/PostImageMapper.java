package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostImage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 帖子配图 Mapper（任务 3.3、3.5）。 */
@Mapper
public interface PostImageMapper extends BaseMapper<PostImage> {

  /**
   * 一屏帖子的配图批量取（任务 3.5）。
   *
   * <p><b>为什么必须有这条</b>：列表一屏 20 条，用 listByPost 逐条查就是 20 次往返；
   * 手册 NFR3 给列表接口定的 P95 是 300ms，光连接往返就烧完了。
   * 排序仍按 (post_id, sort, id)，所以调用方分组后天然是展示顺序，不用再排一次。</p>
   *
   * <p>空集合直接返回空列表，不拼 IN ()：那是一条语法错的 SQL，
   * 而且 MyBatis-Plus 的 in() 在空集合下会生成非法片段，宁可少发一次查询。</p>
   */
  default List<PostImage> listByPosts(List<Long> postIds) {
    if (postIds == null || postIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<PostImage>()
        .in(PostImage::getPostId, postIds)
        .orderByAsc(PostImage::getPostId)
        .orderByAsc(PostImage::getSort)
        .orderByAsc(PostImage::getId));
  }

  /** 按展示顺序取某帖配图，详情接口（任务 3.5）复用。 */
  default List<PostImage> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostImage>()
        .eq(PostImage::getPostId, postId)
        .orderByAsc(PostImage::getSort)
        .orderByAsc(PostImage::getId));
  }
}
