-- =============================================================================
-- 心屿 MindIsle · sql/14_stage4_alter.sql · 阶段 4 增量（AI 对话链路开工前置）
-- 依据：手册 §7.1 任务 4.2 / 4.6 / 4.9
-- =============================================================================
-- 本脚本只有两条 ALTER，全都是「阶段 2 照 ER 文档建表时没料到、阶段 4 真写代码才发现缺列」的补丁。
-- 两条都是幂等的：先用 information_schema 判存在性，再 PREPARE 执行，重复跑不会报 1060/1061。
--
-- 1) chat_message.interrupted —— ER 文档 §3 与 sql/02_ai.sql 都没有这一列。
--    手册任务 4.6 要求「停止生成后已生成部分落库并标 interrupted=1」：半截回复必须存下来
--    （否则用户回看会话时以为模型没答完是自己网络断了），又必须能和完整回复区分开
--    （论文要统计中断率、管理端回看要能过滤）。这是对 ER 文档的一处**已记录偏离**，
--    同步写进 docs/dev-log.md 阶段 4，不改 ER 文档本体。
--
-- 2) emotion_record.sleep_bucket —— 前端打卡表单（views/emotion/EmotionView.vue 第 25 行）
--    有「睡得怎么样」四档，而 sql/03_emotion.sql 的 emotion_record 没有能装它的列。
--    与其静默丢掉用户填的东西，不如加一列可空，并在这里写白它不是 ER 文档里的字段。
--    睡眠档位也是阶段 5 推荐特征的候选来源，留着比丢了有用。
--
-- 第 3 条（补索引）写完现查后被证明是重复索引，已删除，理由留在下面第 3 段原文里。
-- =============================================================================
SET NAMES utf8mb4;

-- 1 -----------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'interrupted');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `chat_message` ADD COLUMN `interrupted` TINYINT NOT NULL DEFAULT 0 COMMENT ''任务4.6 停止生成：1=本次回复被中断，content 只存已生成的部分'' AFTER `degraded`',
  'SELECT ''chat_message.interrupted 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2 -----------------------------------------------------------------------
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emotion_record' AND COLUMN_NAME = 'sleep_bucket');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `emotion_record` ADD COLUMN `sleep_bucket` TINYINT NULL COMMENT ''打卡自带的睡眠档位：0不足4h 1=4-6h 2=6-8h 3=8h+（前端表单字段，ER 文档无此列，理由见本脚本头注）'' AFTER `record_date`',
  'SELECT ''emotion_record.sleep_bucket 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3 —— 本条已被删除，删除理由写在这里，不偷偷少做一件事 -------------------
-- 原计划给 conversation 补 (user_id, last_msg_at) 复合索引，理由是任务 4.2 的会话列表
-- 要按 last_msg_at 倒序取最近 50 条。建完之后现查 SHOW INDEX 才发现 sql/02_ai.sql 里
-- 早就有 idx_user_last(user_id, status, last_msg_at)：会话列表的 SQL 一定带 status='ACTIVE'，
-- 那个索引的最左前缀 + 尾列顺序已经能同时吃掉过滤和排序，再加一条就是纯重复索引
-- —— 只增加写入放大和「两条索引哪条被优化器选中」的排查成本。故本脚本只做上面两条 ALTER。
-- 留证（本轮 SHOW INDEX 的实测输出）：idx_user_last = (user_id,status,last_msg_at)。

-- 现查回执：跑完直接看列在不在，脚本自己打印证据，不靠人回忆
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'interrupted')
    OR (TABLE_NAME = 'emotion_record' AND COLUMN_NAME = 'sleep_bucket'))
ORDER BY TABLE_NAME, COLUMN_NAME;
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation'
GROUP BY INDEX_NAME;