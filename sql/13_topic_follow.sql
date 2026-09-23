-- =============================================================================
-- 心屿 AI 心理陪伴社区 · sql/13_topic_follow.sql · 话题关注（第 33 张物理表）
-- 依据：手册 §6.1 行 3.8「创建话题需 audit_status=待审（防刷）· 关注话题 ·
--       话题页置顶/最新」；需求 FR4.6 的「关注」语义在话题维度上的同款；
--       topic.follow_cnt（表 15）的真相表；手册 §18 Gate3「话题页的计数=关联表 count」。
-- 为什么阶段 3 才出现这张表：表 1–31 是阶段 2 按 ER 文档一次定稿的，当时把「关注」
--       整个词只理解成「关注人」（user_follow）。真到 T3.8 落地才发现它装不下
--       「关注一个话题」，取舍记录见本文件末尾「为什么不复用既有表」。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 33. topic_follow 用户-话题关注（手册 §6.1 行 3.8）
--     topic.follow_cnt 的真相表就是本表：每次关注/取关后按它重算覆盖写
--     （口径与 comment_cnt / like_cnt / report_cnt 完全一致，见
--      PostMapper.refreshCommentCnt 与 refreshLikeCnt 的注释——漂移在结构上不可能发生，
--      而不是「+1 再定期回写」）。
--     uk_topic_follow 让「同一个人对同一个话题反复点关注」只留一行，
--     于是「点两下」和「网络重试」都不会把关注数虚增成 2。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `topic_follow` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '关注人，逻辑外键 user.id。只来自 JWT，客户端没有机会替别人关注',
  `topic_id`    BIGINT UNSIGNED NOT NULL COMMENT '被关注话题，逻辑外键 topic.id',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关注时间。也是「我关注的话题」列表的排序依据（同 user_follow 的口径：新关注在前）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_follow` (`user_id`,`topic_id`),
  KEY `idx_topic` (`topic_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题关注（手册 §6.1 行 3.8 · topic.follow_cnt 的真相表）';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 为什么不复用既有表（T3.8 开工前的取舍，写在这里免得将来有人再想一遍）
--
-- 1) user_action（表 19，FR5.1 隐式反馈埋点）：它的 uk_action 是「按人 + 按目标 + 按动作 +
--    按自然日」幂等，同一个人当天对同一话题的第二次行为会被当成重复埋点吞掉；
--    更要命的是它「没有反向语义」——取关必须精确删掉「关注」那一行，
--    而埋点表的设计是「一行一个信号、可以随便丢、按日聚合」，两者不是一回事。
--    将来 T3.10 会给「关注话题」补一条 +分值的行为埋点，那是它作为推荐输入的派生记录，
--    不是这条关系本身（同「举报入 audit_task 是派生工单、真相在 content_report」的分工）。
-- 2) user_follow（表 5）：它是「人-人」关系，follow_user_id 列的语义是 user.id。
--    把 topic.id 塞进同一列，第一个坏掉的不是存储而是读侧口径：
--    countFollowers(user_id) 会把「关注了这个话题的人数」算进「关注了这个人的粉丝数」，
--    阶段 4 的关注图谱与需求 FR4.6 的「关注 TA 就能在首页看到 TA 的更新」会共用同一批行；
--    更要紧的是账号注销清理（任务 4.21）按 user_id 删 user_follow 时，
--    会连带删掉别人对「话题」的关注。两个语义共用一张表，迟早要在一处 WHERE 里漏判 target 类型。
-- 3) 在 topic 表上存 JSON 用户 id 数组：关注数是高频读、单点写，
--    JSON 数组既给不了 uk 幂等，也给不了「我关注了哪些话题」的索引，pass。
--
-- 本表「没有 deleted 列」，取关是物理 DELETE —— 理由与 user_follow 逐字相同：
-- 唯一索引不认逻辑删除位，留着软删行会让「取消关注之后再也关注不上」变成必然故障
-- （撞 uk_topic_follow）。行为的留痕本来在 user_action（T3.10），这里不重复记账。
-- 同理「不建 updated_at」：这张表的行只有「插入」和「删除」两种命运，没有 UPDATE，
-- updated_at 会恒等于 created_at，只多一个「改错了会撒谎」的面。
-- =============================================================================
