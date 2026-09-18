-- sql/00_create_db_and_user.sql
-- MindIsle (009) 阶段 0 · 任务 0.5 —— 建库 + 建最小权限应用账号
-- 执行方式（root 口令只在交互提示里输入，不落文件/不进 git）：
--   powershell -NoProfile -ExecutionPolicy Bypass -File .\docs\init-db.ps1
--
-- MySQL 9.x 注意：PASSWORD() 函数在 8.0 已移除，9.x 不存在，
-- 必须用 CREATE USER ... IDENTIFIED BY '...'；不要用旧写法 SET PASSWORD ... PASSWORD()。
-- 字符集必须 utf8mb4 + utf8mb4_0900_ai_ci（中文树洞正文 / emoji 依赖 utf8mb4；
-- ngram 全文索引在 04/10 建表脚本里再配，见手册 §5）。

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS mindisle
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 应用账号：只给 mindisle 库的权限，不给 GLOBAL/GRANT OPTION（防止代码被攻破后可建库）
CREATE USER IF NOT EXISTS 'mindisle'@'localhost' IDENTIFIED BY '__APP_USER_PASSWORD__';

-- 口令策略：本机 MySQL 若装了 validate_password 组件，弱口令会在此处报错，
-- 换强口令即可；不要为了跑通去关掉它。
ALTER USER 'mindisle'@'localhost' IDENTIFIED BY '__APP_USER_PASSWORD__';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER,
      CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW, SHOW VIEW,
      TRIGGER, REFERENCES
  ON mindisle.* TO 'mindisle'@'localhost';

FLUSH PRIVILEGES;

SELECT 'database'  AS item, DATABASE() AS value
UNION ALL SELECT 'current_user', CURRENT_USER()
UNION ALL SELECT 'charset', @@character_set_database
UNION ALL SELECT 'collation', @@collation_database
UNION ALL SELECT 'version', VERSION();