-- 把 seed-500-posts.sql 灌进来的量级夹具整批删掉（物理删除：它们本来就不是内容）。
-- 判据用标题前缀「性能量级夹具·」而不是 id 区间 —— 前缀是这批行唯一且可复核的身份标记；
-- 这个前缀由 seed-500-posts.sql 生成，正常用户发帖路径不会撞上它（标题里带中点的机会都有，但带这个完整前缀的没有）。
-- 文件是 UTF-8 无 BOM，mysql 客户端以 --default-character-set=utf8mb4 读它，中文前缀可以直写。
DELETE FROM post WHERE title LIKE '性能量级夹具·%';
SELECT
  (SELECT COUNT(*) FROM post WHERE status='PUBLISHED' AND visibility='public' AND deleted=0) AS pub_public_after,
  (SELECT COUNT(*) FROM post) AS all_rows_after;
