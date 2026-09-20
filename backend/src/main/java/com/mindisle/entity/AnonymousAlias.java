package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 马甲映射 anonymous_alias（需求 §7.2 #3、FR1.4 · 手册 §5.1 表 3）。
 *
 * <p>全站唯一能把「匿名昵称」反查回真实身份的表，因此 {@code revealed_log_id}
 * 只允许指向 admin_op_log，且仅 SUPER 可写（A9 可审计）。</p>
 *
 * <p>没有 deleted 列：马甲是身份映射，不做逻辑删除，撤回匿名只能靠解匿审计。</p>
 */
@Data
@TableName("anonymous_alias")
public class AnonymousAlias {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 user.id。 */
  private Long userId;

  /** 马甲名，如「匿名屿民·阿澜」（需求 FR1.4 口径），最长 32。 */
  private String aliasName;

  /** HOLE / HELP / FEEDBACK / ALL，见 sql/01_account.sql 的 ENUM。 */
  private String scene;

  /** 解匿审计指向 admin_op_log.id；未解匿恒为 null。 */
  private Long revealedLogId;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
