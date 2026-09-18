-- =============================================================================
-- 心屿 MindIsle · sql/07_audit.sql · 内容安全与危机干预域（6 张物理表）
-- 依据：需求 §7.2 #16 #17 #18 #19 · 需求 FR7 内容审核 · 需求 §5.2 L0-L3 处置矩阵 · 需求 §18.3 七类词库
--       · ER 文档 §2.7 §3（23-28）· 手册 §5.1 v1.1.2 补列（audit_record.engine_version / hit_words）
--       · 手册 §5.1 施工补：appeal 更名 post_appeal（明确宿主，避免与后续举报申诉混淆）
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 23. sensitive_word_group 敏感词分组（需求 §18.3 七类，级别与动作逐条对齐该表）
--     关键设计（需求 §18.3 结论）：自伤类词不能走「屏蔽删除」逻辑——删除等于把人推回沉默，
--     故该类 action=TAG：内容照常放行，只打风险标记并触发 L2/L3 卡片 + 工单。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sensitive_word_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(32)     NOT NULL COMMENT '类目名，取需求 §18.3 七类：政治违法/色情低俗/辱骂攻击/自伤自杀/隐私泄露/广告导流/医疗越界词',
  `level`       ENUM('black','grey','risk') NOT NULL COMMENT '黑=拒绝 灰=人审 风险=放行但标记（需求 §18.3 级别列）',
  `action`      ENUM('BLOCK','REVIEW','TAG','IGNORE') NOT NULL COMMENT '命中动作；自伤类固定 TAG（放行但触发 L2/L3，不删除）',
  `hit_scope`   ENUM('user','ai','both') NOT NULL DEFAULT 'user' COMMENT '作用侧：用户输入/AI 输出/双侧；医疗越界词仅 user=ai（BR8）',
  `word_cnt`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '组内词条数，答辩时用于说明词库规模',
  `remark`      VARCHAR(200)    NULL COMMENT '备注：出处与处置说明',
  `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词分组·七类（需求 §7.2 #16 前半 · §18.3）';

-- -----------------------------------------------------------------------------
-- 24. sensitive_word 敏感词词条（需求 §7.2 #16 后半；DFA/Trie 词典表 FR7.1）
--     variant_hash = 变体归一化后的 MD5（全半角/大小写/空格/拼音首字母/形近字/emoji 分隔），
--     用于「同一条词的不同写法只入库一次」；热更新走 Redis 版本号触发各节点重载（FR7.1）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sensitive_word` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_id`     BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 sensitive_word_group.id',
  `word`         VARCHAR(64)     NOT NULL COMMENT '词条原文（需求 §7.2 #16）',
  `variant_hash` CHAR(32)        NULL COMMENT '变体归一化后的 MD5，去重与批量导入用',
  `match_type`   ENUM('contains','regex','whole') NOT NULL DEFAULT 'contains' COMMENT '匹配方式；隐私类走 regex（手机号/QQ/微信/身份证，需求 §18.3）',
  `hit_cnt`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '累计命中次数，词库优化与误报分析用',
  `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用（灰词验证误报率过高时临时停用，不必删行）',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_group` (`group_id`,`status`),
  KEY `idx_variant` (`variant_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词词条·DFA 词典（需求 §7.2 #16 后半）';

-- -----------------------------------------------------------------------------
-- 25. audit_task 审核任务（需求 §7.2 #17；机审→人审队列，L2 SLA 4h / L3 SLA 30min，需求 §5.2）
--     uk_target_pending 保证「同一目标同时只有一个待办任务」，避免重复进队列（ER §3 第 25 行）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_task` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `target_type`  ENUM('post','comment','pm','hole','ai_reply','image') NOT NULL COMMENT '送审对象类型（需求 §7.2 #17）',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '送审对象逻辑主键',
  `source`       ENUM('machine','human','report') NOT NULL COMMENT '任务来源：机审转人审/主动抽检/用户举报（FR4.4 FR7.3）',
  `channel`      ENUM('dfa','llm','image') NOT NULL DEFAULT 'dfa' COMMENT '三通道：DFA 词库 / LLM 语义复检 / 图像审核（FR7.1-FR7.5）',
  `result`       VARCHAR(32)     NULL COMMENT '机审结论原文，如 PASS/REVIEW/BLOCK/L2/L3',
  `risk_score`   DECIMAL(4,3)    NULL COMMENT '风险分 0.000-1.000，级联置信度或 LLM 输出',
  `risk_level`   ENUM('L0','L1','L2','L3') NOT NULL DEFAULT 'L0' COMMENT '风险等级（需求 §5.2 处置矩阵）',
  `assignee_id`  BIGINT UNSIGNED NULL COMMENT '受理管理员，逻辑外键 user.id',
  `status`       ENUM('PENDING','PROCESSING','PASSED','REJECTED','ESCALATED') NOT NULL DEFAULT 'PENDING' COMMENT '任务状态；ESCALATED=升级危机工单',
  `sla_at`       DATETIME(3)     NULL COMMENT '处置时限：L2=+4h、L3=+30min（需求 §5.2），超时管理端标红',
  `remark`       VARCHAR(500)    NULL COMMENT '审核备注',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_target_pending` (`target_type`,`target_id`,`status`),
  KEY `idx_status_sla` (`status`,`sla_at`),
  KEY `idx_assignee` (`assignee_id`,`status`),
  KEY `idx_risk` (`risk_level`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容审核任务（需求 §7.2 #17）';

-- -----------------------------------------------------------------------------
-- 26. audit_record 审核留痕（需求 §7.2 #18 · FR7.7「全链路留痕」，论文可信性素材）
--     手册 §5.1 v1.1.2 补列：engine_version、hit_words（三通道各自一行）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_record` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id`          BIGINT UNSIGNED NULL COMMENT '逻辑外键 audit_task.id；机审直出结论时可为空',
  `target_type`      ENUM('post','comment','pm','hole','ai_reply','image') NOT NULL COMMENT '被审对象类型，冗余便于直查（ER §4.2）',
  `target_id`        BIGINT UNSIGNED NOT NULL COMMENT '被审对象逻辑主键',
  `channel`          ENUM('dfa','llm','image') NOT NULL COMMENT '本条留痕所属通道（FR7.7 三通道各留一行）',
  `model_version`    VARCHAR(64)     NULL COMMENT '模型版本，如 deepseek-v4-pro@2026-09；LLM 通道必填（需求 §12 规范2）',
  `wordlib_version`  VARCHAR(32)     NULL COMMENT '词库版本，如 v0.1；DFA 通道必填，热更新时递增',
  `engine_version`   VARCHAR(32)     NULL COMMENT '手册 §5.1 v1.1.2 补列：DFA/Trie 引擎版本，用于复现实验',
  `hit_words`        VARCHAR(500)    NULL COMMENT '手册 §5.1 v1.1.2 补列：本次命中词条，逗号分隔，上限 500 字符',
  `raw_output`       MEDIUMTEXT      NULL COMMENT '机审原始输出 JSON / LLM 原始回复，排查与回归用',
  `latency_ms`       INT UNSIGNED    NULL COMMENT '本通道耗时毫秒（FR7.6 机审 P95 达标分析）',
  `decision`         VARCHAR(32)     NOT NULL COMMENT '结论：PASS/REVIEW/BLOCK/TAG/L2/L3',
  `risk_score`       DECIMAL(4,3)    NULL COMMENT '该通道给出的风险分',
  `reason`           VARCHAR(500)    NULL COMMENT '结论文本理由，LLM 通道回填便于人审参考',
  `operator_id`      BIGINT UNSIGNED NULL COMMENT '人审操作人，逻辑外键 user.id；机审为空',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '留痕时间，只增不改（论文审计链）',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task` (`task_id`),
  KEY `idx_target` (`target_type`,`target_id`,`created_at`),
  KEY `idx_decision` (`decision`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核全链路留痕（需求 §7.2 #18 · FR7.7）';

-- -----------------------------------------------------------------------------
-- 27. alert_ticket 危机预警工单（需求 §7.2 #19；创新点③ L0-L3—12356 转介闭环的落地载体）
--     需求 §7.2 索引要求原文：「alert_ticket(status,level)」
--     evidence_text 冗余存证据片段：工单独立于原内容（原帖可能被作者删除），保证处置可回溯。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `alert_ticket` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `level`          ENUM('L2','L3') NOT NULL COMMENT '工单级别；L0/L1 只软引导不建单（需求 §5.2）',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '被救助对象，逻辑外键 user.id（ER §2.7）',
  `source_type`    ENUM('chat','post','hole','pm') NOT NULL COMMENT '触发来源：AI 对话/普通帖/树洞/私信（需求 FR5 FR6.6）',
  `source_id`      BIGINT UNSIGNED NULL COMMENT '来源逻辑主键（chat_message.id / post.id / private_message.id）',
  `evidence_text`  VARCHAR(500)    NOT NULL COMMENT '冗余证据片段（ER §4.2），原内容删除后仍可复核',
  `risk_score`     DECIMAL(4,3)    NOT NULL DEFAULT 0 COMMENT '触发时的风险分',
  `trigger_words`  VARCHAR(200)    NULL COMMENT '命中的风险词，逗号分隔',
  `status`         ENUM('pending','claimed','doing','closed','false_positive','expired') NOT NULL DEFAULT 'pending' COMMENT '六态：待认领/已认领/处置中/已闭环/误报/超时失效',
  `assignee_id`    BIGINT UNSIGNED NULL COMMENT '认领人（心理辅导员或超管），逻辑外键 user.id',
  `claim_at`       DATETIME(3)     NULL COMMENT '认领时间，用于算认领时效',
  `close_at`       DATETIME(3)     NULL COMMENT '闭环时间，用于算处置时效（需求 §5.2 SLA）',
  `sla_at`         DATETIME(3)     NULL COMMENT '处置时限：L3=+30min、L2=+4h（需求 §5.2）',
  `handle_note`    VARCHAR(1000)   NULL COMMENT '处置记录：联系情况、是否转介 12356、后续安排（只做记录不做诊断结论，BR3）',
  `followup_at`    DATETIME(3)     NULL COMMENT '回访时间，闭环后 24h 提醒回访',
  `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（仅测试数据清理用，真实工单不删）',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建单时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_level` (`status`,`level`),
  KEY `idx_user` (`user_id`,`created_at`),
  KEY `idx_assignee` (`assignee_id`,`status`),
  KEY `idx_sla` (`sla_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危机预警工单（需求 §7.2 #19 · 创新点③）';

-- -----------------------------------------------------------------------------
-- 28. post_appeal 帖子申诉（手册 §5.1 施工补，原表名 appeal → 更名 post_appeal 明确宿主）
--     uk_post_pending 保证「同一帖同时只有一条待处理申诉」，对应 post.status=APPEALING
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_appeal` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`      BIGINT UNSIGNED NOT NULL COMMENT '被申诉帖子，逻辑外键 post.id',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '申诉人（帖子作者），逻辑外键 user.id',
  `reason`       VARCHAR(500)    NOT NULL COMMENT '申诉理由（FR4.5）',
  `status`       ENUM('PENDING','ACCEPTED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '受理结果；通过则帖子回到 MACHINE_REVIEW 重审',
  `handler_id`   BIGINT UNSIGNED NULL COMMENT '处理管理员，逻辑外键 user.id',
  `handled_at`   DATETIME(3)     NULL COMMENT '处理时间',
  `result_note`  VARCHAR(500)    NULL COMMENT '处理说明，回执给申诉人',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_pending` (`post_id`,`status`),
  KEY `idx_status` (`status`,`created_at`),
  KEY `idx_user` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子违规申诉（手册 §5.1 施工补 · 原 appeal 更名）';

SET FOREIGN_KEY_CHECKS = 1;
