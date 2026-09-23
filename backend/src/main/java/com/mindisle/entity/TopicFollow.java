package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 话题关注关系 topic_follow（任务 3.8 · 手册 §6.1 行 3.8「关注话题」· §5.1 表 33）。
 *
 * <p><b>本表没有 deleted 列，取关就是物理删</b>：口径与 {@link UserFollow} 逐字相同 ——
 * 唯一索引 uk_topic_follow 不认逻辑删除位，留着软删行会让「取消关注之后再也关注不上」
 * 变成必然故障。行为留痕在 user_action（任务 3.10），这里不重复记账。
 * 为什么不并进 user_follow（少建一张表）、不并进 user_action（少写一份 Mapper），
 * 三条理由都写在 {@code sql/13_topic_follow.sql} 末尾，那里是这段取舍的原始出处。</p>
 */
@Data
@TableName("topic_follow")
public class TopicFollow {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 关注发起人。 */
  private Long userId;

  /** 被关注话题。 */
  private Long topicId;

  private LocalDateTime createdAt;
}
