-- ----------------------------------------------------------------
-- 冒烟脚本专用预置数据（docs/smoke.mjs 第 9 步的 30004 用例）
--
-- 为什么单独一个文件、且不进 09_seed.sql：
--   种子数据描述的是「产品上线时库里应该有的东西」，而这条 PENDING 话题唯一的存在理由，
--   是让「挂一个未过审话题」这条分支能被真实 HTTP 打到。话题提交接口属任务 T3.4，
--   还没实现，脚本没有任何合法途径自己造出这条数据；写死 id 又会让脚本在别的库上必然失败，
--   所以这里用 UNIQUE 的 name 做幂等 upsert，跑完把 id 用环境变量传给脚本：
--     SMOKE_PENDING_TOPIC_ID=<id> node docs/smoke.mjs
--
-- 不影响别的断言：/api/topics 走 TopicMapper#listOfficialApproved，只捞 APPROVED，
-- 所以「种子 20 条话题全部读回」这条不会因为多了一行待审而破。
-- 执行：见 docs/dev-log.md「冒烟取证」一节里的 mysql 命令。
-- ----------------------------------------------------------------
USE mindisle;

INSERT INTO topic (name, desc_txt, audit_status, post_cnt, follow_cnt, hot_score, is_official)
VALUES ('冒烟待审话题', '仅用于冒烟脚本的 30004（话题未过审）用例，不是运营内容', 'PENDING', 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE audit_status = 'PENDING', desc_txt = VALUES(desc_txt);

SELECT id, name, audit_status FROM topic WHERE name = '冒烟待审话题';
