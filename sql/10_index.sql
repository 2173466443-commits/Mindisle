-- =============================================================================
-- 10_index.sql  心屿 / MindIsle 二级索引与全文索引补建（需求 §7.2 索引口径 · §6.2 · FR1.8）
-- 内容：本文件只含 ALTER TABLE … ADD INDEX，不含 CREATE TABLE / INSERT。
-- 顺序：必须在 01~08 建表之后执行；全文索引建表时不内联，原因见下方「为什么不内联」。
-- -----------------------------------------------------------------------------
-- 为什么不内联进 04_community.sql：
--   1. FULLTEXT 建索引会触发整表重建，与 DDL 建表混写会让「先建表后灌词库」的执行顺序难以拆分；
--   2. ngram 解析器需要 ngram_token_size 在实例初始化期确定，索引后置便于改参重建；
--   3. 与需求 §7.2 逐条核对出来的补漏索引集中在一个文件里，答辩时可直接对照讲「索引是按需加的」；
--   4. 二次开发/复现者若只要最小库，可跳过本文件（功能降级为 LIKE 检索，不报错）。
-- -----------------------------------------------------------------------------
-- ⚠ 中文全文检索两个坑（手册 §5.1 规范 5，实测环境 MySQL 8.x）：
--   坑 1：ngram_token_size 默认 2，两字词（如「失眠」「考研」）不会被索引，
--         必须 my.cnf 设 ngram_token_size=1 并重启实例后重建索引；
--   坑 2：innodb_ft_min_token_size 默认 3，单字/双字查询词会被停用规则挡掉，
--         同样需要改配置并重启，且改后需重建 FULLTEXT 索引才生效。
--   结论：V1 检索链路保留 LIKE 降级——MATCH … AGAINST 命中 0 行或参数不可用时，
--         自动回落到 title/content LIKE + 话题名 LIKE，保证功能可用与实验可复现。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- I1 帖子全文检索（FR1.8 站内搜索：标题 + 正文，按 ngram 切中文词）
-- -----------------------------------------------------------------------------
ALTER TABLE `post`
  ADD FULLTEXT INDEX `ft_title_content` (`title`,`content`) WITH PARSER ngram;

-- -----------------------------------------------------------------------------
-- I2 话题名检索与输入联想（FR1.7 新建话题查重 + 搜索框 suggest）
-- -----------------------------------------------------------------------------
ALTER TABLE `topic`
  ADD FULLTEXT INDEX `ft_topic_name` (`name`) WITH PARSER ngram;

-- -----------------------------------------------------------------------------
-- I3 首页「综合热度」feed 排序（需求 §6.2：置顶优先，其次质量分，最后发布时间）
-- 现有 idx_status_pub 只覆盖 (status, published_at)，无法表达 is_top + quality_score 排序，
-- 排序列全部进索引才能免 filesort。
-- -----------------------------------------------------------------------------
ALTER TABLE `post`
  ADD INDEX `idx_hot_feed` (`status`,`is_top`,`quality_score`,`published_at`);

-- -----------------------------------------------------------------------------
-- I4 话题广场取帖的覆盖索引（需求 §6.2 话题维度召回：topic_id → post_id 集合）
-- 现有 idx_topic(topic_id) 仍要回表取 post_id；补 (topic_id, post_id) 后，
-- 召回阶段可用覆盖索引一次取完，离线推荐批量任务扫描代价显著下降。
-- -----------------------------------------------------------------------------
ALTER TABLE `post_topic`
  ADD INDEX `idx_topic_post` (`topic_id`,`post_id`);

-- -----------------------------------------------------------------------------
-- I5 「我的-点赞/收藏」按时间倒序分页（FR1.4 交互记录列表）
-- uk_action 最左是 user_id 但下一列是 target_type，按 action_type + 时间翻页会 filesort。
-- -----------------------------------------------------------------------------
ALTER TABLE `post_like`
  ADD INDEX `idx_user_action_time` (`user_id`,`action_type`,`deleted`,`created_at`);

-- -----------------------------------------------------------------------------
-- I6 管理端操作日志全局倒序（BR10 处置留痕：审核员/管理员每次写操作都要可回溯）
-- admin_op_log 现有三条索引最左都是过滤列，「不带筛选直接翻最近 100 条」走不到索引。
-- -----------------------------------------------------------------------------
ALTER TABLE `admin_op_log`
  ADD INDEX `idx_created` (`created_at`);

-- -----------------------------------------------------------------------------
-- 执行核对：查 information_schema 确认 6 条索引到位（手工执行，勿放自动流程）
-- SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols
--   FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = 'mindisle'
--    AND INDEX_NAME IN ('ft_title_content','ft_topic_name','idx_hot_feed',
--                        'idx_topic_post','idx_user_action_time','idx_created')
--   GROUP BY TABLE_NAME, INDEX_NAME;
-- 期望返回 6 行。MySQL 8 不支持 ADD INDEX IF NOT EXISTS，重跑前先跑上面的查询；
-- 若已存在需回滚：
-- ALTER TABLE `post` DROP INDEX `ft_title_content`;
-- ALTER TABLE `topic` DROP INDEX `ft_topic_name`;
-- ALTER TABLE `post` DROP INDEX `idx_hot_feed`;
-- ALTER TABLE `post_topic` DROP INDEX `idx_topic_post`;
-- ALTER TABLE `post_like` DROP INDEX `idx_user_action_time`;
-- ALTER TABLE `admin_op_log` DROP INDEX `idx_created`;

