package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 帖子状态流转留痕 post_status_log（手册 §5.1 施工补表 · BR10「任何状态变更可追溯」）。
 *
 * <p>只增不改不删：这张表是「机审有没有偷偷吞帖」的唯一物证，也是 Gate6
 * 「四态流转日志完整」的举证来源，因此刻意不加 deleted 位，也不挂 {@code @TableLogic}。</p>
 *
 * <p>{@code operatorId} 为 null 表示系统流转（DDL 注释要求 reason 里标 system），
 * 人工处置（任务 6.1）才写管理员 id 与必填理由。</p>
 */
@Data
@TableName("post_status_log")
public class PostStatusLog {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 post.id。 */
  private Long postId;

  private String fromStatus;

  private String toStatus;

  /** 操作人，系统流转为 null。 */
  private Long operatorId;

  /** 变更原因，系统流转形如 system|engine=v0.1|hits=2，人工处置必填自然语言理由。 */
  private String reason;

  private LocalDateTime createdAt;
}
