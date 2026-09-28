-- =============================================================================
-- 心屿 MindIsle · sql/15_stage4_backfill.sql · 阶段 4 收口增量（T3.10 埋点 / T4.19 反馈 / T4.21 隐私中心）
-- 依据：手册 §7.5 补齐任务表 + §7.5「T4.21 隐私中心」四条口径 + 需求 FR1.5 FR1.6 FR5.1 FR2.7 BR11 D11
-- =============================================================================
-- 本脚本四条改动，全部幂等（information_schema 判存在 → PREPARE 执行），重复跑不报 1060/1061/1050。
-- 为什么不并进 sql/14：14 是「阶段 4 开工前置」（缺列才补），本脚本是「阶段 4 收口补齐」（新增能力才建表）。
-- 两者的判据来自手册不同小节，混在一个文件里会让「哪条改动对应哪个任务」失去可追溯性。
--
-- 1) user_action.target_type 加枚举值 'message'（T4.19）
--    sql/05_recommend.sql 建表时 target_type 只有 post/comment/topic/user，那是按需求 §7.2 #9 的
--    「内容行为」设计的；而手册 T4.19 要求 AI 消息反馈「写 user_action（action_type=ai_feedback，
--    带 message_id）」。行为对象是「一条 AI 回复」，四种里一种都不是：
--    硬塞成 'post' 会让 target_id 空间撞车（帖子 id 与消息 id 都是自增，(post,17) 到底指哪条？），
--    另起一张表又违背手册「并入 user_action」的指令。正解是加一个枚举值，
--    并把「message 的 target_id 就是 chat_message.id、与 message_id 列同值」写进列注释钉死。
--    MODIFY COLUMN 是整条列定义重建，ENUM 顺序与 COMMENT 必须一起写全，漏一项就悄悄改掉另一项。
--
-- 2) export_task 隐私导出任务表（T4.21）
--    需求 §7.2 表清单里没有这张表（导出属 v1.1 才补进范围的 T4.21），手册只给了一句口径：
--    「异步生成 export_task，下载链接 24h 失效」。本表就是那句口径的物化。两个取舍写在现场：
--      · 下载链接用随机 token 而不是自增 id：token 会进 URL、浏览器历史和 access log，
--        它必须既不可枚举、又不暴露「这个人导出过几次」。故 CHAR(64) 随机十六进制 + 唯一索引。
--      · expire_at 单独一列而不是从 created_at 派生：将来若有「重新发一次有效期」的动作，
--        写死成派生值就没有落点。列名 fmt 而不是 format：FORMAT 是 MySQL 保留字风险词。
--
-- 3) user.deactivate_at + user.purge_at（T4.21 注销状态机）
--    user.status 的 ENUM 里本来就有 DELETED，需求把它定义为「冷静期」而不是「已清除」，
--    但没有一列记录「什么时候提交的注销」——而 BR11 的判据是「满 30 天」，
--    没有提交时刻就算不出到期时刻。purge_at 是 deactivate_at + 30 天的物化冗余：
--    DataRetentionJob 每天扫这张表找该清除的人，用 DATE_ADD 函数算会废掉索引扫描，
--    冗余一列换一次范围扫描是划算的；唯一写入方是 PrivacyService，不存在第二处赋值。
--
-- 4) 补三条索引（隐私清除与导出扫描都要用，建表脚本里没有）
--    · user(status, purge_at)：DataRetentionJob 的谓词正好是这两个条件
--    · export_task 的两条已随建表给出
--    · user_action(user_id, target_type, created_at)：按人取「近 N 天的帖子行为」时，
--      现有 idx_user_time(user_id,created_at) 不带 target_type，过滤要回表
-- =============================================================================
SET NAMES utf8mb4;

-- 1 -----------------------------------------------------------------------
SET @has_msg := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_action' AND COLUMN_NAME = 'target_type'
    AND COLUMN_TYPE LIKE '%message%');
SET @ddl := IF(@has_msg = 0,
  'ALTER TABLE `user_action` MODIFY COLUMN `target_type` ENUM(''post'',''comment'',''topic'',''user'',''message'') NOT NULL COMMENT ''目标类型：帖子/评论/话题/用户（需求 §7.2 #9）+ message（手册 T4.19：一条 AI 回复，target_id 即 chat_message.id，与 message_id 列同值）''',
  'SELECT ''user_action.target_type 已含 message，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2 -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `export_task` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键（仅内部用，不进下载链接）',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id：导出者本人，只允许本人取（FR1.5）',
  `fmt`         ENUM('json','csv') NOT NULL COMMENT '导出格式：单 JSON 文件 / 多张 CSV 打成的 ZIP（手册 §7.5 双格式）',
  `status`      ENUM('PENDING','RUNNING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '任务态：请求即落 PENDING，后台跑完改 SUCCESS 或 FAILED',
  `file_path`   VARCHAR(255)    NULL COMMENT '相对导出根目录的路径；库里不存绝对路径，换机器不失效',
  `file_bytes`  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '产物字节数，取文件时现查文件系统做交叉校验',
  `row_counts`  JSON            NULL COMMENT '每类各导出多少行 {post:12,...}：Gate4「条数与库内一致」的判据',
  `token`       CHAR(64)        NULL COMMENT '下载口令（随机 32 字节十六进制），不落日志不回显在列表里',
  `expire_at`   DATETIME(3)     NULL COMMENT '链接失效时刻 = 生成成功时 +24h（手册 §7.5）；过期即 404 并删文件',
  `error_text`  VARCHAR(500)    NULL COMMENT 'FAILED 时的面向用户短句，不含堆栈与内部路径',
  `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：软删留「谁在何时导出过」这条合规痕迹',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时刻',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态变更时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_user_status` (`user_id`,`status`),
  KEY `idx_expire` (`status`,`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='隐私数据导出任务（手册 T4.21 · 需求 FR1.5）';

-- 3 -----------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'deactivate_at');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `user` ADD COLUMN `deactivate_at` DATETIME(3) NULL COMMENT ''提交注销的时刻（T4.21 状态机 active→cooling→purged 的 cooling 入口时间戳）；冷静期内登录即撤回注销并置回 NULL'' AFTER `status`',
  'SELECT ''user.deactivate_at 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'purge_at');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `user` ADD COLUMN `purge_at` DATETIME(3) NULL COMMENT ''deactivate_at + 30 天的物化冗余（BR11）：DataRetentionJob 按它做索引范围扫描；唯一写入方是 PrivacyService'' AFTER `deactivate_at`',
  'SELECT ''user.purge_at 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4 -----------------------------------------------------------------------
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_status_purge');
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `user` ADD INDEX `idx_status_purge` (`status`,`purge_at`)',
  'SELECT ''user.idx_status_purge 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_action' AND INDEX_NAME = 'idx_user_type_time');
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `user_action` ADD INDEX `idx_user_type_time` (`user_id`,`target_type`,`created_at`)',
  'SELECT ''user_action.idx_user_type_time 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 现查回执：脚本自己打印证据，不靠人回忆
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'user_action' AND COLUMN_NAME = 'target_type')
    OR (TABLE_NAME = 'user' AND COLUMN_NAME IN ('status','deactivate_at','purge_at')))
ORDER BY TABLE_NAME, COLUMN_NAME;
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('export_task','user','user_action')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;
