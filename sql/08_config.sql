-- =============================================================================
-- 心屿 MindIsle · sql/08_config.sql · 配置与通知域（3 张物理表）
-- 依据：需求 §7.2 #20 #23 #24 前半 · 需求 FR8.6 系统配置 · 需求 §5.3 BR10（特权操作 100% 留痕）
--       · ER 文档 §2.8 §3（29-31）· 手册 §5.1 v1.1.2 补列（value_type / group_key / updated_by）
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 29. sys_config 系统参数（需求 §7.2 #23；阈值/权重/Prompt 版本全部可配，论文消融实验直接改这里
--     手册 §5.1 v1.1.2 补列：value_type、group_key、updated_by；需求 §12 规范1「阈值不写死在代码里」
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_config` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cfg_key`     VARCHAR(64)     NOT NULL COMMENT '参数键，点分命名空间，如 risk.cascade_threshold（需求 §7.2 #23）',
  `cfg_value`   VARCHAR(2000)   NOT NULL COMMENT '参数值，按 value_type 解析；json 型存 JSON 字符串',
  `value_type`  ENUM('string','int','decimal','bool','json') NOT NULL DEFAULT 'string' COMMENT '值类型（手册 §5.1 v1.1.2 补列），读取时据此转型',
  `group_key`   VARCHAR(32)     NOT NULL DEFAULT 'misc' COMMENT '分组（手册 §5.1 v1.1.2 补列）：rec/risk/prompt/audit/ai，管理端按组分页展示',
  `editable`    TINYINT         NOT NULL DEFAULT 1 COMMENT '1=管理端可改 0=只读（如需发版才能改的开关）',
  `remark`      VARCHAR(200)    NULL COMMENT '参数含义与取值范围说明，同时是论文参数对照表来源',
  `updated_by`  BIGINT UNSIGNED NULL COMMENT '最后修改人，逻辑外键 user.id（手册 §5.1 v1.1.2 补列）',
  `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cfg_key` (`cfg_key`),
  KEY `idx_group` (`group_key`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数（需求 §7.2 #23 · FR8.6）';

-- -----------------------------------------------------------------------------
-- 30. notify_message 站内通知（需求 §7.2 #20；点赞/评论/关注/私信/审核/危机/报告八类）
--     需求 §7.2 索引要求原文：「notify_message(user_id,is_read)」→ 追加 id 做游标分页
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify_message` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '接收人，逻辑外键 user.id（需求 §7.2 #20）',
  `type`       ENUM('like','comment','follow','pm','system','audit','crisis','report') NOT NULL COMMENT '八类通知：互动四类 + 系统 + 审核结果 + 危机关怀 + 周报/报告',
  `title`      VARCHAR(100)    NOT NULL COMMENT '标题，列表页展示',
  `content`    VARCHAR(500)    NOT NULL COMMENT '正文；crisis 类只写关怀话术与求助入口，不含任何诊断结论（BR3）',
  `ref_type`   VARCHAR(16)     NULL COMMENT '跳转对象类型 post/comment/user/report/conversation',
  `ref_id`     BIGINT UNSIGNED NULL COMMENT '跳转对象逻辑主键',
  `is_read`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读，红点计数走覆盖索引',
  `read_at`    DATETIME(3)     NULL COMMENT '已读时间',
  `deleted`    TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（用户清理通知列表）',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`,`id`),
  KEY `idx_type` (`type`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知（需求 §7.2 #20）';

-- -----------------------------------------------------------------------------
-- 31. admin_op_log 管理员操作留痕（需求 §7.2 #24 前半；BR10 解匿/查私信/导出 100% 留痕）
--     本表是 PIPL 第 29、55 条「敏感个人信息处理留痕」的落地证据，只增不改不删。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `admin_op_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人，逻辑外键 user.id（需求 §7.2 #24 前半）',
  `operator_role` VARCHAR(32) NULL COMMENT '操作时角色快照 ADMIN/SUPER/COUNSELOR，角色变更后仍可追责',
  `action`      VARCHAR(64)     NOT NULL COMMENT '动作码，如 REVEAL_ANONYMOUS / READ_PM / EXPORT_DATA / AUDIT_PASS / UPDATE_CONFIG',
  `target`      VARCHAR(128)    NULL COMMENT '被操作对象描述，如 post:1024 / user:88，BR10 要求可追溯',
  `target_id`   BIGINT UNSIGNED NULL COMMENT '被操作对象主键（可为空，如全局导出）',
  `ip`          VARCHAR(45)     NULL COMMENT '来源 IP，兼容 IPv6 长度 45',
  `user_agent`  VARCHAR(255)    NULL COMMENT '客户端标识，异常行为分析用',
  `detail`      TEXT            NULL COMMENT '参数快照与理由文本，解匿必须填事由（FR2.5）',
  `result`      VARCHAR(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAIL / DENIED',
  `cost_ms`     INT UNSIGNED    NULL COMMENT '本次操作服务端耗时毫秒',
  `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（合规要求保留，默认永不使用）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间，只增不改',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_operator_time` (`operator_id`,`created_at`),
  KEY `idx_action` (`action`,`created_at`),
  KEY `idx_target` (`target`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员特权操作审计（需求 §7.2 #24 前半 · BR10）';

SET FOREIGN_KEY_CHECKS = 1;
