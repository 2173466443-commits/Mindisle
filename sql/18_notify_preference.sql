-- =============================================================================
-- 心屿 MindIsle · sql/18_notify_preference.sql · 通知偏好（第 35 张物理表）
-- 依据：需求 FR9.4「偏好设置：可关闭点赞/评论类打扰，**审核结果与危机相关通知不可关闭**」；
--       手册 §15 T3.16「notify_preference 四类开关（审核/危机类不可关）」、
--       §18 Gate3 判据「关闭类仍落库不计红点，危机类开关置灰」。
-- =============================================================================
--
-- 为什么这张表到现在才建：阶段 3 的 T3.16 只做了「读侧」（列表 + 红点 + 一键已读），
-- 手册当时把「按类型开关」划进同一格任务并留了 ◐，欠话原话写在 §15 T3.16 行末尾
-- （"② 按类型开关的 notify_preference 表与设置界面"）。不建的代价是那句判据只能永远悬着：
-- 写侧只有一条 NotifyService#write 的通道，没有任何地方能问「这个人要不要收这一类」。
--
-- 为什么是「关一种存一行」而不是「一行 JSON 存八个开关」：
--   1) 主键 (user_id, type) 天然幂等 —— 同一个人重复保存同一类只会 UPDATE 那一行，
--      而 JSON 列的读-改-写是并发的经典坑（两个人在同一毫秒各关一类，后写的把先写的覆盖掉）；
--   2) 注销清除（T4.21）走 PrivacyDomains 的通用归属谓词 user_id = ?，JSON 列进不了那条谓词，
--      等于要给导出与清除各开一条特例 —— 而这两处的表清单必须逐字相同（见 PrivacyDomains 类注释）；
--   3) 查一个开关是一次主键点查 WHERE user_id = ? AND type = ?，写通知的热路径上只要这一件事。
--
-- 为什么**没有**默认行（不给每个用户预插八行 enabled=1）：
--   「库里没有这一行」本身就是「没改过、按默认接收」，预插八行会让「从没设置过的人」和
--   「把八个开关都手动打开过的人」在库里长得一样，而那两件事在界面上要说两句话。
--   代价是读侧必须把「缺行」翻译成 enabled=true —— 翻译只写在 NotifyPreferenceService 一处。
--
-- 🔴 为什么关闭≠不写：Gate3 的判据是「关闭类仍落库不计红点」。
--   通知行是「这件事发生过」的证据（与 notify_message 不做级联清理同一个取舍），
--   关掉的是打扰（红点 + 实时推送），不是记录本身。写侧的实现见 NotifyService#write 的偏好闸门。
--   enabled 只影响 is_read 与推送，绝不影响 INSERT —— 所以这一列不需要「删除通知」的第二语义。
--
-- 为什么不建 created_at / updated_by：
--   偏好只能本人改（JWT 取 user_id，接口不接受外部传入的 user_id，与点赞关注举报同一口径），
--   留痕表是给「管理员对别人做了什么」准备的，这里每一行的改动人就是行里那个人。
--   created_at 也不要：这一行的命运是「UPSERT 之后一直活着」，创建时间与第一次改动时间
--   在实现上会被压成同一个值，那不如只留 updated_at 这一个真话。
--
-- 为什么不复用 sys_config：那是全局配置（提示词、阈值、热线），一张键值表装不了「按人的开关」，
--   而且它属隐私注册表里的「全局表 · 不进导出包」（Link.NONE），个人的通知偏好若存在那里，
--   注销时既导不出也删不掉 —— 那是 FR1.6 的直接违反。
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 35. notify_preference 通知偏好（需求 FR9.4 · 手册 §15 T3.16）
--     复合主键 (user_id, type)：既是幂等键，也是「一次点查取一个开关」的访问路径。
--     不建 id 代理主键的理由见文件头第 3 条 —— 这张表没有任何一行需要被别人引用。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify_preference` (
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '接收人，逻辑外键 user.id。只来自 JWT：客户端没有机会替别人设偏好（需求 BR4/A9）',
  `type`       ENUM('like','comment','follow','pm','system','audit','crisis','report') NOT NULL COMMENT '与 notify_message.type 同一套八值，改一边必须改另一边（对账钉在 NotifyPreferenceServiceTest）',
  `enabled`    TINYINT NOT NULL DEFAULT 1 COMMENT '1=接收 0=关闭。关闭的含义是「不落红点、不实时推」，不是「不写通知行」（Gate3 判据）',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后一次真正改动开关的时刻；重复保存同一个值不会被刷新（ON UPDATE 只在行有实际变更时触发）',
  PRIMARY KEY (`user_id`,`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知偏好（需求 FR9.4 · 关闭类仍落库不计红点 · 审核/危机/系统三档不可关）';

-- 现查（跑完本文件后核这三行）：表已建、列数、复合主键两列。
SELECT 'table' AS item, COUNT(*) AS cnt
  FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'notify_preference'
UNION ALL
SELECT 'columns', COUNT(*) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'notify_preference'
UNION ALL
SELECT 'pk_cols', COUNT(*) FROM information_schema.key_column_usage
 WHERE table_schema = DATABASE() AND table_name = 'notify_preference' AND column_name IN ('user_id','type');

SELECT COUNT(*) AS total_tables FROM information_schema.tables WHERE table_schema = DATABASE();
