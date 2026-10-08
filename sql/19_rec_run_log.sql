-- =============================================================================
-- 心屿 MindIsle · sql/19_rec_run_log.sql · 推荐重算台账（第 36 张物理表）
-- 依据：手册 §10.5「每次重算留一行历史」· §15 阶段 8 队列① · 答辩 D7「离线作业日志可查」
-- =============================================================================
--
-- 🔴 为什么现在才建：阶段 7 收口时 `AdminRecController` 的类注释把可追溯性押在三样东西上
--   （应用日志里的 channel share、`recommend_result.calc_at`、`RecommendJob` 内存里的 lastFailure），
--   代价写得很清楚——重启即丢。现查 `information_schema` 当时是 0 行、表根本不存在，
--   所以那一轮拒绝用 `GET /api/admin/rec/status` 的现算读数冒充「有历次台账」。本文件补的就是这一格。
--
-- 为什么一行要记三种结局（SUCCESS / FAILED / SKIPPED_BUSY）：
--   只记成功的那张表回答不了答辩现场唯一要紧的问题——「这一轮为什么没数」。
--   跳过也留行，于是「作业被配置关了」「上一轮还没结束」「真抛异常了」在库里是三行长相不同的记录。
--   `SKIPPED_BUSY` 的 finished_at = started_at、duration_ms = 0：它确实什么都没做，
--   把 0 写成「小于 1 毫秒的四舍五入」是给未来的自己埋雷。
--
-- 为什么 `channel_share` 存 JSON 字符串而不是拆成四列：
--   通道集合是要长的（阶段 8 之后可能再加多路召回、向量召回），拆列等于每加一个通道做一次 DDL；
--   而这串数的唯一用途是「这一轮各通道贡献了多少」的展示与对账，从来不是 SQL 的过滤条件
--   ——不参与的列就不该为它牺牲查询写法。`ai_call_log` 的 `prompt_version` 同一取舍。
--
-- 为什么没有 `deleted` 列：日志表只增不删（与 `ai_call_log` 逐字同一口径）。
--   软删一张台账等于把「重算失败过」这件事抹掉，而它是运营复盘唯一的原始证据。
--   `PrivacyDomains` 里它登记为 Link.NONE + Action.KEEP：行里没有 user.id，也没有内容正文，
--   注销清除不该碰它，导出包也不该带它（导出的是这个人的数据，不是平台的作业记录）。
--
-- 为什么 `error_text` 用 VARCHAR(1000) 而不是 TEXT：写进去之前服务层已经截到 1000，
--   栈深不进库——堆栈的家是日志文件，台账只要一句「为什么红」。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 36. rec_run_log 推荐重算台账（手册 §10.5 · 阶段 8 队列① · 答辩 D7）
--     一次重算一行，成功失败跳过都算一次；写入口在 RecommendJob#rebuildOnce 的收尾处。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `rec_run_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trigger_type`    ENUM('schedule','manual') NOT NULL DEFAULT 'schedule' COMMENT '触发口：定时轮 / 管理端「立即重算」按钮。两条路共用同一把重入锁与同一个方法，只有记账上要分开',
  `status`          ENUM('SUCCESS','FAILED','SKIPPED_BUSY') NOT NULL COMMENT '本轮结局。跳过也留行，否则「没人点」「被配置关」「上一轮没结束」三件事在库里长得一样',
  `started_at`      DATETIME(3) NOT NULL COMMENT '本轮开始时刻',
  `finished_at`     DATETIME(3) NOT NULL COMMENT '本轮结束时刻；SKIPPED_BUSY 时等于 started_at',
  `duration_ms`     BIGINT NOT NULL DEFAULT 0 COMMENT '本轮占用毫秒，成功/失败/跳过都记（§11.5 性能线与 Gate8 压测的历史底片）',
  `mode`            VARCHAR(16) NULL COMMENT 'Summary.mode：emotion-on / emotion-off，或 rebuildAll 提前返回时的原因短码；SKIPPED_BUSY 为 NULL',
  `user_cnt`        INT NOT NULL DEFAULT 0 COMMENT '本轮覆盖的用户数，取已被 mindisle.schedule.rec-rebuild-user-limit 截断后的真值',
  `quality_rows`    INT NOT NULL DEFAULT 0 COMMENT '用户质量分写入行（Summary.qualityRows）',
  `topic_rows`      INT NOT NULL DEFAULT 0 COMMENT '话题热度重算行（Summary.topicRows），topic.hot_score 唯一的来源',
  `similarity_rows` INT NOT NULL DEFAULT 0 COMMENT 'item_similarity 写入行；0 = 相似位只剩话题兜底与质量分榜（判据 D6）',
  `result_rows`     INT NOT NULL DEFAULT 0 COMMENT 'recommend_result 净写入行；0 = 在线侧全体退热度兜底，个性化没生效（判据 D1）',
  `channel_share`   VARCHAR(500) NULL COMMENT '通道占比原样存的 JSON 串，答辩 D7 的证据物；不参与 SQL 过滤所以不拆列',
  `error_text`      VARCHAR(1000) NULL COMMENT 'FAILED 时的异常 toString；SUCCESS 与 SKIPPED_BUSY 为 NULL，服务层落库前截到 1000',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时刻，与 finished_at 的差就是这条台账自己的写入延迟',
  PRIMARY KEY (`id`),
  KEY `idx_run_started` (`started_at`),
  KEY `idx_run_status` (`status`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐重算台账（手册 §10.5 · 阶段8 队列① · 每次重算留一行历史，成功/失败/跳过都算一次）';

-- 现查（跑完本文件后核这四行）：表已建、列数 15、两个二级索引、库内总表数应为 36。
SELECT 'table' AS item, COUNT(*) AS cnt
  FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'rec_run_log'
UNION ALL
SELECT 'columns', COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'rec_run_log'
UNION ALL
SELECT 'secondary_indexes', COUNT(DISTINCT index_name) FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'rec_run_log' AND index_name <> 'PRIMARY'
UNION ALL
SELECT 'total_tables', COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();
