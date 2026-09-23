-- =============================================================================
-- 心屿 MindIsle · sql/12_report.sql · 举报域（第 32 张物理表）
-- 依据：需求 FR4.7「理由分类（攻击辱骂/色情低俗/违法/泄露隐私/自伤风险/广告）+ 描述 +
--       截图证据；举报即刻生成 audit_task」、FR4.4/FR7.3（post.report_cnt 达阈值转
--       HUMAN_REVIEW）、手册 §6.5 第 6 条（六个理由字面量与 self-harm 的额外提示）、
--       手册 §6.1 行 3.11（举报入 audit_task(source=report)）
-- 为什么阶段 3 才出现这张表：表 1–31 是阶段 2 按 ER 文档一次定稿的，举报当时被寄望于
--       复用 user_action（行为埋点）与 audit_task（处置队列）。真到 T3.11 落地才发现两者
--       都装不下它，取舍记录见本文件末尾「为什么不复用既有表」。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 32. content_report 内容举报（需求 FR4.7）
--     post.report_cnt 的真相表就是本表：每次举报后按它重算覆盖写（同 comment_cnt /
--     like_cnt 的口径，见 PostMapper.refreshCommentCnt 与 refreshLikeCnt 的注释）。
--     uk_reporter_target 让「同一个人对同一条内容反复点举报」只留一行——
--     这不是防重复提交的技巧，而是一条产品口径：重复举报不叠加权重（FR4.7 + BR2 幂等），
--     否则一个人对着不喜欢的人连点二十次就能凭空造出二十个「被举报次数」。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content_report` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `reporter_id`   BIGINT UNSIGNED NOT NULL COMMENT '举报人，逻辑外键 user.id。举报不匿名：匿名举报等于把「用举报消灭异见」的成本降到 0，也让人审无法回问补充证据',
  `target_type`   ENUM('post','comment') NOT NULL DEFAULT 'post' COMMENT '被举报对象类型。评论举报（同一套判据、另一条宿主）留到这里再开，本阶段只写 post',
  `target_id`     BIGINT UNSIGNED NOT NULL COMMENT '被举报对象逻辑主键（post.id / comment.id）',
  `post_id`       BIGINT UNSIGNED NOT NULL COMMENT '冗余所属帖子：评论举报也要能顺着一条 SQL 找到宿主帖；也是管理端按帖聚合举报的索引前缀',
  `author_id`     BIGINT UNSIGNED NOT NULL COMMENT '冗余被举报内容的作者：工单与处置直接读，省一次 JOIN，且原帖被删后仍能定位到人',
  `reason`        ENUM('spam','abuse','sexual','privacy','self-harm','other') NOT NULL COMMENT '理由分类，取值照抄手册 §6.5 第 6 条字面量；与 FR4.7 六类的对应见 ReportService.REASONS 的注释',
  `description`   VARCHAR(500)    NULL COMMENT '补充描述。列宽 500 而服务端上限 200 字：余量是给配置放宽用的，不是随手写的数字',
  `evidence_urls` VARCHAR(1000)   NULL COMMENT '截图证据，逗号分隔的相对路径，最多 3 张（FR4.7 截图证据）。用列而不是子表：上限 3 且从不按单张图检索，建表是净增复杂度',
  `status`        ENUM('PENDING','ACCEPTED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '举报处置结果，由管理端 T6.1 回填；ACCEPTED 才会下架帖子（需求状态机 PUBLISHED→TAKEDOWN）',
  `handler_id`    BIGINT UNSIGNED NULL COMMENT '处理管理员，逻辑外键 user.id',
  `handled_at`    DATETIME(3)     NULL COMMENT '处理时间，算举报响应时效用',
  `result_note`   VARCHAR(500)    NULL COMMENT '给举报人的处置说明，经 notify_message(type=report) 回执（T6.1）',
  `deleted`       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除，仅供测试数据清理（同 sensitive_word 的口径）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '举报时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reporter_target` (`reporter_id`,`target_type`,`target_id`),
  KEY `idx_target` (`target_type`,`target_id`,`status`),
  KEY `idx_post` (`post_id`,`created_at`),
  KEY `idx_reporter` (`reporter_id`,`created_at`),
  KEY `idx_status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容举报（需求 FR4.7 · post.report_cnt 的真相表）';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 为什么不复用既有表（T3.11 开工前的取舍，写在这里免得将来有人再想一遍）
--
-- 1) user_action（表 19，FR5.1 隐式反馈埋点）：它只有 user/target/action/score/created_at
--    这一族列，没有 reason、没有描述、没有证据，且 uk_action 是按日幂等——
--    同一个人当天对同一帖的第二条举报会被当成重复埋点吞掉。埋点表的价值是
--    「一行一个信号、可以随便丢」，举报的价值恰恰是「一行一条可举证的申诉、一条都不能丢」。
-- 2) 只写 audit_task.remark（表 25）：remark 是 VARCHAR(500) 的自由文本，
--    而 uk_target_pending(target_type,target_id,status) 决定同一帖子同时只能有一条
--    PENDING 任务，于是第 2 到第 N 个举报人没有落点，report_cnt 永远 <= 1，
--    FR4.4 的「达阈值自动转人审」直接失效。
-- 3) post_like（表 18）：它是「关系开关态」表（点赞与取消），举报没有反向操作，
--    硬塞进 action_type 会让「取消举报」变成一个无处安放的问题。
--
-- 结论：举报必须有自己的真相表，audit_task 只是它派生的处置工单，
--        user_action 只是它派生的推荐负反馈（T3.10 埋点里 report = -5）。
-- =============================================================================
