-- =============================================================================
-- 心屿 MindIsle · sql/03_emotion.sql · 情绪档案域（2 张物理表）
-- 依据：需求 §7.2 #14 · ER 文档 §2.3 §3 · 手册 §7.5 T4.20（weekly_report DDL）
-- 说明：weekly_report 采用「ER 文档 §3 + 手册 T4.20 的列名并集」，以 ER 文档主命名为准，
--       保留手册特有的 checkin_days / summary_text / generator / shared_flag，见 dev-log 记录。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 10. emotion_record 情绪记录（需求 §7.2 #14，主动打卡 + 被动识别双来源）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `emotion_record` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `source`         ENUM('checkin','chat','post') NOT NULL COMMENT '来源：主动打卡/AI 对话被动识别/发帖被动识别（FR3.1 FR3.2）',
  `ref_id`         BIGINT UNSIGNED NULL COMMENT '来源明细 id（chat_message.id 或 post.id），打卡为空',
  `text_snippet`   VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '触发文本片段，脱敏后截断存储，供人工回看',
  `label`          VARCHAR(16)     NOT NULL COMMENT '7 类情绪之一：joy/trust/anger/sadness/fear/disgust/neutral（需求 §1.5 创新点 1）',
  `intensity`      TINYINT         NOT NULL DEFAULT 2 COMMENT '强度 1-5，打卡由用户自选',
  `valence`        TINYINT         NOT NULL DEFAULT 0 COMMENT '效价 -1 负 0 中 1 正（ER 文档 §5 取舍 2：分档语义用 TINYINT）',
  `confidence`     DECIMAL(4,3)    NOT NULL DEFAULT 0.000 COMMENT '识别置信度，级联策略阈值 0.55 的判据',
  `channel`        ENUM('dict','llm','manual') NOT NULL DEFAULT 'dict' COMMENT '识别通道，词典先行/LLM 兜底，消融实验分组键',
  `model_version`  VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '词典版本或模型+提示词版本，保证实验可复现',
  `record_date`    DATE            NOT NULL COMMENT '归集日期，周报与趋势图按此聚合',
  `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_date` (`user_id`,`record_date`),
  KEY `idx_label` (`label`,`record_date`),
  KEY `idx_source` (`source`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='情绪记录（需求 §7.2 #14）';

-- -----------------------------------------------------------------------------
-- 11. weekly_report 情绪周报（手册 T4.20；uk_user_week 幂等，定时任务可重跑）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `weekly_report` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `week_start`       DATE            NOT NULL COMMENT '统计周起始日（周一）',
  `week_end`         DATE            NOT NULL COMMENT '统计周结束日（周日）',
  `checkin_days`     TINYINT         NOT NULL DEFAULT 0 COMMENT '本周打卡天数（手册 T4.20）',
  `record_cnt`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '本周情绪记录条数',
  `dominant_label`   VARCHAR(16)     NULL COMMENT '主导情绪标签',
  `avg_intensity`    DECIMAL(4,2)    NOT NULL DEFAULT 0.00 COMMENT '平均强度',
  `positive_ratio`   DECIMAL(4,3)    NOT NULL DEFAULT 0.000 COMMENT '正向情绪占比 valence=1 的条数/总条数',
  `trend_delta`      DECIMAL(4,2)    NOT NULL DEFAULT 0.00 COMMENT '与上周平均效价的差值，可正可负',
  `insight`          JSON            NULL COMMENT '分情绪计数等明细统计，前端雷达/柱图直接用',
  `summary_text`     VARCHAR(1000)   NULL COMMENT '结论文字，LLM 生成或模板兜底',
  `generator`        ENUM('llm','template') NOT NULL DEFAULT 'template' COMMENT '生成方式：熔断/超时/解析失败即 template（FR3.5 硬要求）',
  `shared_flag`      TINYINT         NOT NULL DEFAULT 0 COMMENT '是否已去标识分享为一条 post',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_week` (`user_id`,`week_start`),
  KEY `idx_week` (`week_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='情绪周报（手册 T4.20）';

SET FOREIGN_KEY_CHECKS = 1;
