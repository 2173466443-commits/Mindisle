-- §6.4 第 3 条「500 条帖量级下 /api/posts P95 ≤ 500ms」的前置量级夹具（2026-09-23）
--
-- 为什么需要它：今天库里 PUBLISHED+public 只有 208 行（接口 total=203），拿这个量级测出来的
-- P95 不能声称「500 条量级达标」。本文件把可复制的公开实名帖复制 3 份、取满 300 行，
-- 让广场量级越过 500；测完必须跑同目录的 cleanup-500-posts.sql 整批删掉 ——
-- 夹具留在库里会让论文截图里的「广场共 N 条」变成一句假话。
--
-- 跑法（口令一律走仓库外的 rootpwd.cnf，见全局复利日志第 10/11 轮）：
--   & '<run-sql.cmd 绝对路径>' '<本文件绝对路径>' '<输出绝对路径>'
-- 只复制「实名、无马甲、已过审、公开、未删」的帖：匿名帖的 alias_id 是一对一关系，
-- 复制它会造出「同一个马甲号发了一堆帖」这种库里根本不该出现的形状。
INSERT INTO post
  (user_id, type, title, content, visibility, is_anonymous, alias_id, status,
   view_cnt, like_cnt, comment_cnt, collect_cnt, report_cnt,
   emotion_primary, emotion_score, risk_level, quality_score, is_top, is_featured,
   auto_destroy_at, published_at, deleted)
SELECT
   p.user_id,
   'normal',
   -- title 是 VARCHAR(100)，直接 CONCAT 有越界风险（严格模式会整条语句失败），一律 LEFT 截断
   LEFT(CONCAT('性能量级夹具·', p.title, '·', n.k, '·', p.id), 100),
   CONCAT(p.content, '\n\n（这一段是 §6.4 第 3 条的量级夹具，测完由 cleanup-500-posts.sql 整批删除。）'),
   'public',
   0,
   NULL,
   'PUBLISHED',
   0, 0, 0, 0, 0,
   p.emotion_primary, p.emotion_score,
   'L0',
   p.quality_score,
   0, 0,
   NULL,
   -- 发布时间往前错开，保证它们排在既有内容之后，不会把广场首屏挤满夹具
   DATE_SUB(NOW(3), INTERVAL (n.k * 1440 + p.id) MINUTE),
   0
FROM post p
JOIN (SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3) n
WHERE p.status = 'PUBLISHED' AND p.visibility = 'public' AND p.deleted = 0
  AND p.is_anonymous = 0 AND p.alias_id IS NULL
ORDER BY p.id, n.k
LIMIT 300;
