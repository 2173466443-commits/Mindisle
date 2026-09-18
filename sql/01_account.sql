-- =============================================================================
-- 心屿 MindIsle · sql/01_account.sql · 账户与隐私域（6 张物理表）
-- 依据：需求 §7.2 #1 #2 #3 #10 #11 + PIPL 第 29 条（user_consent）· 手册 §5.1 DDL 规范 · ER 文档 §2.1 §3
-- 全局约定：InnoDB + utf8mb4_0900_ai_ci；主键 BIGINT UNSIGNED AUTO_INCREMENT；逻辑删除 deleted；
--          只建索引不建物理外键（逻辑外键 + 应用层校验，理由见 ER 文档 §4）；计数列 INT UNSIGNED DEFAULT 0。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 1. user 账号主表（需求 §7.2 #1；手册 §5.1 v1.1.2 补列 last_login_ip）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`         VARCHAR(32)     NOT NULL COMMENT '登录名，唯一即规则 uk_username',
  `password`         VARCHAR(60)     NOT NULL COMMENT 'BCrypt 强度 10 摘要，禁止明文与可逆加密',
  `nickname`         VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '展示昵称（社区域可改）',
  `avatar`           VARCHAR(255)    NULL COMMENT '头像 URL 或内置头像编号',
  `email`            VARCHAR(128)    NULL COMMENT '选填，用于找回；注销后清空',
  `status`           ENUM('ACTIVE','MUTED','BANNED','DELETED') NOT NULL DEFAULT 'ACTIVE' COMMENT '账号态：DELETED 即注销冷静期，不出现在推荐与搜索',
  `role`             ENUM('USER','ADMIN','SUPER') NOT NULL DEFAULT 'USER' COMMENT '角色，权限唯一来源（A9 可审计）',
  `ai_style`         ENUM('warm','rational','humorous') NOT NULL DEFAULT 'warm' COMMENT 'AI 陪伴人格风格（FR2.2）',
  `reg_source`       VARCHAR(32)     NOT NULL DEFAULT 'web' COMMENT '注册来源，用于 A5 转化统计',
  `agree_privacy_at` DATETIME(3)     NULL COMMENT '首次同意隐私政策时间；全过程留痕在 user_consent',
  `last_login_at`    DATETIME(3)     NULL COMMENT '最近登录时间',
  `last_login_ip`    VARCHAR(45)     NULL COMMENT '手册 §5.1 v1.1.2 补列：45 位兼容 IPv6，仅风控与滥用溯源',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0 否 1 是',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_status` (`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号主表（需求 §7.2 #1）';

-- -----------------------------------------------------------------------------
-- 2. user_profile 资料与偏好（需求 §7.2 #2；与 user 1 对 1，主键即外键，无自增 id）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_profile` (
  `user_id`                BIGINT UNSIGNED NOT NULL COMMENT '与 user.id 同值，1 对 1',
  `grade`                  ENUM('FRESH','SOPH','JUNIOR','SENIOR','OTHER') NOT NULL DEFAULT 'OTHER' COMMENT '年级，冷启动分群',
  `school`                 VARCHAR(64)     NULL COMMENT '学校/院系，仅用于同校话题聚合，可留空',
  `gender`                 ENUM('M','F','OTHER','UNSET') NOT NULL DEFAULT 'UNSET' COMMENT '敏感字段，单独同意后方可采集',
  `interest_tags`          JSON            NULL COMMENT '冷启动标签召回用（需求 §6.3 冷启动），数组字符串',
  `bio`                    VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '个性签名，同样过内容安全链',
  `onboarding_done`        TINYINT         NOT NULL DEFAULT 0 COMMENT '新手引导是否完成（U1）',
  `emotion_share_consent`  TINYINT         NOT NULL DEFAULT 0 COMMENT '情绪内容分享单独同意，撤回即置 0 并写 user_consent',
  `risk_flag`              TINYINT         NOT NULL DEFAULT 0 COMMENT '手册 §5.1 v1.1.2 补列：曾触发 L2/L3 的脱敏标记，仅计数不存内容',
  `following_cnt`          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '关注数（Redis 计数回写 BR2）',
  `follower_cnt`           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '粉丝数',
  `post_cnt`               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '发帖数',
  `deleted`                TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_grade` (`grade`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户资料与偏好（需求 §7.2 #2）';

-- -----------------------------------------------------------------------------
-- 3. user_consent 同意与撤回流水（ER 文档 §5 取舍 4：PIPL 第 29 条举证）
--    追加式表：禁止 UPDATE、禁止 DELETE，撤回写成一条 action=WITHDRAW 的新行。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_consent` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `consent_type`     ENUM('TERMS','PRIVACY','SENSITIVE_INFO','EMOTION_SHARE','CRISIS_CONTACT') NOT NULL COMMENT '同意事项类型：敏感个人信息/情绪分享/紧急联系人须单独同意',
  `action`           ENUM('GRANT','WITHDRAW') NOT NULL COMMENT '授予或撤回，二者都新增行，不覆盖历史',
  `content_version`  VARCHAR(16)     NOT NULL COMMENT '当时的协议版本号，举证关键',
  `source_page`      VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '触发同意的页面/弹窗标识',
  `ip`               VARCHAR(45)     NULL COMMENT '操作 IP',
  `user_agent`       VARCHAR(255)    NULL COMMENT '终端标识，截断存储',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间（本表无 updated_at：只追加）',
  PRIMARY KEY (`id`),
  KEY `idx_user_type_time` (`user_id`,`consent_type`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='隐私同意与撤回留痕（PIPL 第 29 条）';

-- -----------------------------------------------------------------------------
-- 4. anonymous_alias 马甲映射（需求 §7.2 #3，全站唯一可回溯表）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `anonymous_alias` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `alias_name`       VARCHAR(32)     NOT NULL COMMENT '马甲名，如 匿名树洞·雾屿 07',
  `scene`            ENUM('HOLE','HELP','FEEDBACK','ALL') NOT NULL DEFAULT 'ALL' COMMENT '生效场景，树洞/求助/建议分域匿名',
  `revealed_log_id`  BIGINT UNSIGNED NULL COMMENT '解匿审计指向 admin_op_log.id，仅 SUPER 可写（A9）',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_alias_scene` (`user_id`,`scene`),
  KEY `idx_alias_name` (`alias_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='匿名马甲映射（需求 §7.2 #3）';

-- -----------------------------------------------------------------------------
-- 5. user_follow 关注（需求 §7.2 #10）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_follow` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '关注发起人',
  `follow_user_id`   BIGINT UNSIGNED NOT NULL COMMENT '被关注人',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow_pair` (`user_id`,`follow_user_id`),
  KEY `idx_follow_user` (`follow_user_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关注关系（需求 §7.2 #10）';

-- -----------------------------------------------------------------------------
-- 6. user_block 黑名单（需求 §7.2 #11，拉黑后双向不可见）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_block` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '拉黑发起人',
  `block_user_id`    BIGINT UNSIGNED NOT NULL COMMENT '被拉黑用户',
  `reason`           VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '选填理由',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '拉黑时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_pair` (`user_id`,`block_user_id`),
  KEY `idx_blocked` (`block_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户黑名单（需求 §7.2 #11）';

SET FOREIGN_KEY_CHECKS = 1;
