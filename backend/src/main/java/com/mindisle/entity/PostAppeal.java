package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 内容申诉 post_appeal（需求 FR7.6 · 手册 §9.1「申诉：作者一次性申诉 → 二级队列 → 管理员裁定，终态不可再诉」）。
 *
 * <p>表在 {@code sql/07_audit.sql:143} 就建好了，阶段 3 只留了 DDL 没有写入方，
 * 本阶段（T6.4）是它第一次有真正的读写入口。</p>
 *
 * <p><b>「一次性」这件事由唯一键保证，不由代码保证</b>：{@code uk_post_pending(post_id, status)}
 * 意味着同一条帖子上最多只能有一行 PENDING。申诉被裁定后 status 变成 ACCEPTED/REJECTED，
 * 那一行就退出唯一键的 PENDING 名额，于是作者可以再提一次 —— 这与「终态不可再诉」相冲突，
 * 所以终态判断必须在服务层显式做（见 {@code AppealService#assertAppealable}），
 * 唯一键只是兜住并发双击。这条取舍写在类注释里而不是写在方法里，
 * 因为读 DDL 的人最先在这里找语义。</p>
 */
@Data
@TableName("post_appeal")
public class PostAppeal {

  /** 与 DDL 的 ENUM 逐字一致（大写）。 */
  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_REJECTED = "REJECTED";

  /** 裁定通过时帖子回到的状态：作者赢回可见性。 */
  public static final String POST_RESTORE_STATUS = "PUBLISHED";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 被处置的帖子，逻辑外键 post.id。 */
  private Long postId;

  /** 申诉人 = 帖子作者，逻辑外键 user.id。只有作者能诉，举报人不能替他诉。 */
  private Long userId;

  /** 申诉理由，FR7.6 要求必填；列宽 500，服务层限 300 字留余量。 */
  private String reason;

  /** PENDING / ACCEPTED / REJECTED。 */
  private String status;

  /** 裁定管理员，逻辑外键 user.id。 */
  private Long handlerId;

  /** 裁定时间；PENDING 恒为 null，用它区分「谁还没办」。 */
  private LocalDateTime handledAt;

  /** 给作者的裁定说明，经 notify_message(type=audit) 回执。 */
  private String resultNote;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}