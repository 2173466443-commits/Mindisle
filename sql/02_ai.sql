-- =============================================================================
-- 心屿 MindIsle · sql/02_ai.sql · AI 对话域（3 张物理表）
-- 依据：需求 §7.2 #12 #13 #24 后半 · ER 文档 §2.2 §3 · 手册 §5.1 v1.1.2 补列 ai_call_log.prompt_version
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 7. conversation AI 会话（需求 §7.2 #12）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `title`        VARCHAR(64)     NOT NULL DEFAULT '新的对话' COMMENT '取首条用户消息摘要，可重命名',
  `style`        ENUM('warm','rational','humorous') NOT NULL DEFAULT 'warm' COMMENT '本会话人格，可与 user.ai_style 不同',
  `summary`      VARCHAR(500)    NULL COMMENT '长对话滚动摘要，控制上下文 token（FR2.4）',
  `last_msg_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '列表排序用',
  `status`       ENUM('ACTIVE','ARCHIVED','DELETED') NOT NULL DEFAULT 'ACTIVE' COMMENT '删除即逻辑删除，注销时物理清除（T4.21）',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_last` (`user_id`,`status`,`last_msg_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 会话（需求 §7.2 #12）';

-- -----------------------------------------------------------------------------
-- 8. chat_message AI 消息（需求 §7.2 #13；情绪与风险标签在本行落地，是实验数据的来源）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id`  BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 conversation.id',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '冗余归属，便于按用户导出与清除（T4.21）',
  `role`             ENUM('user','assistant','system') NOT NULL COMMENT '消息角色',
  `content`          MEDIUMTEXT      NOT NULL COMMENT '正文；assistant 侧存净化后的 Markdown',
  `tokens_in`        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '输入 token',
  `tokens_out`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '输出 token',
  `model`            VARCHAR(64)     NULL COMMENT '实际使用的模型名，降级时记 fallback 模型',
  `prompt_version`   VARCHAR(32)     NULL COMMENT '手册 §5.1 v1.1.2 补列：提示词版本，A/B 与回归定位必需',
  `emotion_label`    VARCHAR(16)     NULL COMMENT '用户消息的 7 类情绪标签（FR3.1 被动识别）',
  `emotion_score`    DECIMAL(4,3)    NULL COMMENT '情绪置信度 0.000-1.000',
  `emotion_channel`  ENUM('dict','llm','manual') NULL COMMENT '识别通道：词典/LLM/人工标注，用于级联策略消融实验',
  `risk_level`       ENUM('L0','L1','L2','L3') NOT NULL DEFAULT 'L0' COMMENT '风险等级，L2/L3 触发危机链路（FR5）',
  `feedback`         ENUM('NONE','UP','DOWN') NOT NULL DEFAULT 'NONE' COMMENT '用户对回答的评价（FR2.6）',
  `latency_ms`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '整段生成耗时',
  `degraded`         TINYINT         NOT NULL DEFAULT 0 COMMENT '是否走了降级话术（BR6 可用性优先）',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_conv_time` (`conversation_id`,`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_risk` (`risk_level`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 消息（需求 §7.2 #13）';

-- -----------------------------------------------------------------------------
-- 9. ai_call_log AI 调用与用量（需求 §7.2 #24 后半；一次外部调用一行，含失败）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_call_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         BIGINT UNSIGNED NULL COMMENT '可空：离线批算与定时任务无归属用户',
  `scene`           ENUM('chat','emotion','risk','audit','summary','report','embed') NOT NULL COMMENT '调用场景，预算熔断按场景统计（FR2.7）',
  `model`           VARCHAR(64)     NOT NULL COMMENT '模型名',
  `prompt_version`  VARCHAR(32)     NULL COMMENT '手册 §5.1 v1.1.2 补列',
  `tokens_in`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '输入 token',
  `tokens_out`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '输出 token',
  `cost_cent`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '成本，单位：分（预算以分为整数计，避免浮点误差）',
  `first_token_ms`  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '首字延迟 TTFT，性能指标 N2',
  `latency_ms`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '总耗时',
  `success`         TINYINT         NOT NULL DEFAULT 1 COMMENT '是否成功',
  `error`           VARCHAR(255)    NULL COMMENT '错误摘要，禁止记录密钥与完整提示词',
  `trace_id`        VARCHAR(32)     NULL COMMENT '与日志 MDC traceId 一致，可全链路追踪',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '调用时间',
  PRIMARY KEY (`id`),
  KEY `idx_time` (`created_at`),
  KEY `idx_user_scene` (`user_id`,`scene`,`created_at`),
  KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 调用用量审计（需求 §7.2 #24）';

SET FOREIGN_KEY_CHECKS = 1;
