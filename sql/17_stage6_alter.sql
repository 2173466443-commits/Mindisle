-- =============================================================================
-- 心屿 MindIsle · sql/17_stage6_alter.sql · 阶段 6 增量（审核链路与管理端开工前置）
-- 依据：手册 §9.1 审核链路、§9.2 A4/A6、§15 T6.1/T6.5/T6.6 · 需求 FR4.4 FR4.5 FR7.3 BR6 BR10
-- =============================================================================
-- 三条 ALTER，全部幂等（information_schema 判存在性 → PREPARE 执行），重复跑不报 1060/1061，
-- 姿势照 sql/14_stage4_alter.sql。写之前先现查了库：mute_until 不存在、两张表的 target_type
-- 都没有 topic 档，所以这三条都是「真缺」，不是「以为缺」。
--
-- 1) user.mute_until —— 需求 BR6 说「禁言」，sql/01_account.sql 只给了 user.status 的
--    ENUM('ACTIVE','MUTED','BANNED','DELETED')，即「是不是被禁言」，没有「禁到什么时候」。
--    管理端 A6 的禁言档位是 1/7/30 天（手册 §9.2），没有到期时间就只有两条路：
--    要么靠人工记得去解（一定会忘），要么靠定时任务全表扫（等于把状态存了两份）。
--    加一列可空 DATETIME(3)：NULL = 不是限时禁言（历史数据与人工长期禁言都成立），
--    有值 = 到期自动恢复。读取侧（PostingQuotaService.statusOf）判到期即按 ACTIVE 放行，
--    写侧由 UserManageService 落值、MuteExpiryJob 把 status 改回 ACTIVE —— 自愈优先，作业只是把
--    库里的事实追上判据，不是判据本身。这是对 ER 文档的一处已记录偏离，写进 docs/dev-log.md。
--
-- 2)(3) audit_task.target_type / audit_record.target_type 加 'topic' ——
--    话题（T3.8）的名字与简介是公开内容，也会被举报、也要人审，但建表时 ENUM 里只有
--    post/comment/pm/hole/ai_reply/image，于是 TopicService 送审时只能把 target_type 写成
--    'post' 或者干脆不送审——两者都是把审计链写脏。现查 SELECT DISTINCT target_type
--    FROM audit_task 确认库里确实没有 topic 行，属于「加了枚举值不会与历史数据打架」。
--    两张表必须同一轮改：audit_record.target_type 是冗余列，只改一张就会出现
--    「任务能建、留痕写不进去」，而留痕失败在代码里是被吞掉的（不能因为审计写失败而回滚业务）。
-- =============================================================================
SET NAMES utf8mb4;

-- 1 -----------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'mute_until');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `user` ADD COLUMN `mute_until` DATETIME(3) NULL COMMENT ''BR6 禁言到期时间（任务 T6.6 A6：1/7/30 天）。NULL=非限时禁言或未被禁言；有值且早于当前时间即视为已恢复，读取侧自愈 + MuteExpiryJob 落库'' AFTER `status`',
  'SELECT ''user.mute_until 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2 -----------------------------------------------------------------------
SET @has_topic := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_task' AND COLUMN_NAME = 'target_type'
    AND COLUMN_TYPE LIKE '%topic%');
SET @ddl := IF(@has_topic = 0,
  'ALTER TABLE `audit_task` MODIFY COLUMN `target_type` ENUM(''post'',''comment'',''pm'',''hole'',''ai_reply'',''image'',''topic'') NOT NULL COMMENT ''送审对象类型（需求 §7.2 #17）；topic 档由 sql/17_stage6_alter.sql 追加，T3.8 话题名/简介的举报与机审走这里''',
  'SELECT ''audit_task.target_type 已含 topic，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3 -----------------------------------------------------------------------
SET @has_topic := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_record' AND COLUMN_NAME = 'target_type'
    AND COLUMN_TYPE LIKE '%topic%');
SET @ddl := IF(@has_topic = 0,
  'ALTER TABLE `audit_record` MODIFY COLUMN `target_type` ENUM(''post'',''comment'',''pm'',''hole'',''ai_reply'',''image'',''topic'') NOT NULL COMMENT ''被审对象类型，冗余便于直查（ER §4.2）；与 audit_task 同档，必须同轮改，见本脚本头注第 2)(3) 条''',
  'SELECT ''audit_record.target_type 已含 topic，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 现查回执：跑完直接看列与枚举，脚本自己打印证据 --------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'user' AND COLUMN_NAME = 'mute_until')
    OR (TABLE_NAME = 'audit_task' AND COLUMN_NAME = 'target_type')
    OR (TABLE_NAME = 'audit_record' AND COLUMN_NAME = 'target_type'))
ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT COUNT(*) AS audit_task_rows_with_topic FROM audit_task WHERE target_type = 'topic';
