package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 关注关系 user_follow（任务 3.6 · 需求 FR4.6、§7.2 #10 · 手册 §5.1 表 5）。
 *
 * <p><b>本表没有 deleted 列，取关就是物理删</b>：DDL 定稿时它的定位是「关系」而不是「行为」，
 * 关系不存在时留着软删行只会让 {@code uk_follow_pair} 一直占位、无法再次关注同一人
 * （唯一索引不认逻辑删除位）。行为留痕本来就在 {@code user_action}（任务 3.10），
 * 所以这里不重复记账。与 post_like 的口径差别是<b>有意为之</b>，两处都写在类注释里，
 * 免得后来人「统一」成一种而把另一种改坏。</p>
 */
@Data
@TableName("user_follow")
public class UserFollow {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 关注发起人。 */
  private Long userId;

  /** 被关注人。列名不是 target_user_id：DDL 定稿在先，这里不擅自改名。 */
  private Long followUserId;

  private LocalDateTime createdAt;
}
