-- =============================================================================
-- 心屿 MindIsle · sql/16_weekly_share.sql · 阶段 4 增量（任务 T4.20 ③：周报去标识分享）
-- 依据：手册 §7.5「分享内容去标识：只带昵称或马甲名，不带情绪原始明细；
--       shared_flag = 1 时生成一条普通 post 并走完整审核链（T3.2）」
-- =============================================================================
--
-- 为什么加列而不是只看 shared_flag：shared_flag=1 只说明「分享过」，
-- 用户连点两次「分享」时服务层拿不到上一次生成的帖子 id，只能再发一条 ——
-- 而这一条要走完整审核链，重复发既占配额又让广场出现三张一样的周报。
-- 所以补一列 shared_post_id 做幂等锚点：非空即「已经发过，直接回那一条」。
--
-- ⚠ 本脚本不新建表：全库表数仍是 34 张（文档里的绝对数量口径见手册 §15 收工口径）。
-- 幂等写法照 14_stage4_alter.sql：先查 information_schema，再 PREPARE/EXECUTE。

SET NAMES utf8mb4;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weekly_report' AND COLUMN_NAME = 'shared_post_id');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `weekly_report` ADD COLUMN `shared_post_id` BIGINT UNSIGNED NULL COMMENT ''任务T4.20 去标识分享生成的帖子 id：非空即已分享，重复点分享幂等返回它'' AFTER `shared_flag`',
  'SELECT ''weekly_report.shared_post_id 已存在，跳过'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 现查（跑完必须看到 1 与 34）
SELECT COUNT(*) AS has_shared_post_id FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'weekly_report' AND COLUMN_NAME = 'shared_post_id';
SELECT COUNT(*) AS table_count FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';
