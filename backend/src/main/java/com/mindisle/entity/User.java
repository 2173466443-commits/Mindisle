package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 账号主表 user（需求 §7.2 #1 · 手册 §5.1 表 1）。
 *
 * <p>列名映射依赖 MyBatis-Plus 默认的 snake_case 到 camelCase 规则，故不逐列写 {@link TableField}。
 *
 * <p><b>偏差记录（见 docs/dev-log.md 阶段 2）</b>：MySQL 侧 status / role / ai_style 是 ENUM 列，
 * 这里刻意用 String 而不是 Java enum，避免 MybatisEnumTypeHandler 在未注册时把未知值读成 null，
 * 也让新增枚举值不必改代码发版。业务判断集中用常量比较（见 AuthService）。
 */
@Data
@TableName("user")
public class User {

  /** 主键，自增。 */
  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 登录名，唯一索引 uk_username。 */
  private String username;

  /** BCrypt 摘要，禁止明文；出参永不返回（@JsonIgnore 兜底）。 */
  @JsonIgnore
  private String password;

  /** 展示昵称。 */
  private String nickname;

  /** 头像 URL 或内置头像编号。 */
  private String avatar;

  /** 选填邮箱。 */
  private String email;

  /** ACTIVE / MUTED / BANNED / DELETED。 */
  private String status;

  /** USER / ADMIN / SUPER，权限唯一来源（需求 A9 可审计）。 */
  private String role;

  /** AI 陪伴人格风格：warm / rational / humorous（FR2.2）。 */
  private String aiStyle;

  /** 注册来源，用于 A5 转化统计。 */
  private String regSource;

  /** 首次同意隐私政策时间；完整留痕在 user_consent。 */
  private LocalDateTime agreePrivacyAt;

  /** 最近登录时间。 */
  private LocalDateTime lastLoginAt;

  /** 最近登录 IP，45 位兼容 IPv6，仅风控与滥用溯源。 */
  private String lastLoginIp;

  /** 逻辑删除标记（需求 §12 规范 5：注销走冷静期，不物理删）。 */
  @TableLogic
  @JsonIgnore
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
