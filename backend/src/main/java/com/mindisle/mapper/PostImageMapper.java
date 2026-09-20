package com.mindisle.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.PostImage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 帖子配图 Mapper（任务 3.3、3.5）。 */
@Mapper
public interface PostImageMapper extends BaseMapper<PostImage> {

  /** 按展示顺序取某帖配图，详情接口（任务 3.5）复用。 */
  default List<PostImage> listByPost(long postId) {
    return selectList(new LambdaQueryWrapper<PostImage>()
        .eq(PostImage::getPostId, postId)
        .orderByAsc(PostImage::getSort)
        .orderByAsc(PostImage::getId));
  }
}
