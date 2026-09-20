package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 帖-话题关联 post_topic（需求 §7.2 #7）。
 *
 * <p>uk_post_topic 保证同一帖挂同一话题不会重复计数；本表无 deleted 位，
 * 换话题就是删关联行（任务 3.8 编辑话题时再考虑）。</p>
 */
@Data
@TableName("post_topic")
public class PostTopic {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  private Long postId;

  private Long topicId;

  private LocalDateTime createdAt;
}
