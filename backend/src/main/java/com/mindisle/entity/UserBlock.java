package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 拉黑关系 user_block（任务 T5.6 前置 · 需求 FR6.7、§7.2 #11「拉黑后双向不可见」· sql/01_account.sql 第 6 表）。
 *
 * <p><b>这张表的 DDL 早在阶段 0 就建好了，而后端直到阶段 5 才有第一行实现</b>——
 * 需求把它排在私信之前是想清楚的：拉黑的唯一硬效果就是「私信发不过去」，
 * 没有私信的站点上「拉黑」只是一条没人读的备注。本轮补它的动机因此不是「顺手」，
 * 而是 FR6.6/FR6.7 在私信里构成同一个判据的两面。</p>
 *
 * <p><b>没有 deleted 列，解除拉黑就是物理 DELETE</b>：与 {@link UserFollow} 完全同口径，
 * 理由也同一条——{@code uk_block_pair} 不认逻辑删除位，留着软删行会让「解除之后再拉黑」
 * 永久撞键。别把 post_like 那套软删模板套过来，那两处当初是分别按「行为」和「关系」定的。</p>
 *
 * <p><b>{@code reason} 是选填</b>，而且它<b>只进管理端</b>（阶段 6 A3 的黑名单页）：
 * 被拉黑的人永远看不到理由，甚至看不到「被谁拉黑了」这件事本身——
 * 前端在私信侧只回一句「对方已开启隐私保护」，见
 * {@link com.mindisle.common.ErrorCode#PM_BLOCKED} 与 {@code PmService} 的注释。</p>
 */
@Data
@TableName("user_block")
public class UserBlock {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 拉黑发起人。 */
  private Long userId;

  /** 被拉黑人。列名照 DDL，不是 blocked_user_id。 */
  private Long blockUserId;

  /** 选填理由，DDL 列宽 200；不展示给对方，只给管理端。 */
  private String reason;

  private LocalDateTime createdAt;
}
