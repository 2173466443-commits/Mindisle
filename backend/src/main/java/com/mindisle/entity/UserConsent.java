package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 隐私同意与撤回留痕 user_consent（PIPL 第 29 条举证 · 手册 §5.1 表 3）。
 *
 * <p><b>追加式表</b>：禁止 UPDATE、禁止 DELETE，撤回写成一条 action=WITHDRAW 的新行。
 * 因此本表既没有 updated_at，也没有 deleted 列，实体里也不出现这两个字段。
 */
@Data
@TableName("user_consent")
public class UserConsent {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 user.id（本库不建物理外键，见 sql 目录说明）。 */
  private Long userId;

  /** TERMS / PRIVACY / SENSITIVE_INFO / EMOTION_SHARE / CRISIS_CONTACT。 */
  private String consentType;

  /** GRANT 或 WITHDRAW。 */
  private String action;

  /** 当时的协议版本号，举证关键字段。 */
  private String contentVersion;

  /** 触发同意的页面/弹窗标识。 */
  private String sourcePage;

  /** 操作 IP。 */
  private String ip;

  /** 终端标识，截断存储。 */
  private String userAgent;

  /** 操作时间（本表只有 created_at）。 */
  private LocalDateTime createdAt;
}
