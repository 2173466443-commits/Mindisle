-- =============================================================================
-- 心屿 MindIsle · sql/06_pm.sql · 私信域（1 张物理表）
-- 依据：需求 §7.2 #15 · 需求 FR6 私信与互助 · 需求 §5.3 BR10（解匿与查看私信 100% 留痕 admin_op_log）
--       · ER 文档 §2.6 §3（22）· 手册 §5.1 v1.1.2 补列（status 五态 + risk_level）
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 22. private_message 私聊消息（需求 §7.2 #15；WebSocket+STOMP 推送，同表双角色不区分收发方分表）
--     需求 §7.2 索引要求原文：「private_message(from,to,created_at)」→ 落地为 idx_pair(from,to,id)：
--     会话按 id 单调递增即为时间序，游标分页用 id 比 created_at 更稳（同毫秒不丢消息），
--     故以 (from_user_id,to_user_id,id) 建复合索引，语义等价且分页无歧义。
--     client_msg_id 做客户端幂等：断线重发不会产生两条消息。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `private_message` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，同时作为会话内排序游标',
  `from_user_id`   BIGINT UNSIGNED NOT NULL COMMENT '发送方，逻辑外键 user.id（需求 §7.2 #15）',
  `to_user_id`     BIGINT UNSIGNED NOT NULL COMMENT '接收方，逻辑外键 user.id；同表双角色，ER §2.6',
  `client_msg_id`  VARCHAR(64)     NOT NULL COMMENT '客户端生成的幂等 ID（UUID），重试去重用，配合 to_user_id 唯一',
  `msg_type`       ENUM('text','image','system') NOT NULL DEFAULT 'text' COMMENT '消息类型：文本/图片/系统提示（如对方已开启匿名）',
  `content`        VARCHAR(2000)   NOT NULL COMMENT '文本内容或图片 URL；入库前经敏感词与 XSS 净化（需求 §18.3）',
  `risk_level`     ENUM('L0','L1','L2','L3') NOT NULL DEFAULT 'L0' COMMENT '风险等级：私信同样过危机级联，命中自伤词建 alert_ticket（需求 §5.2）',
  `status`         ENUM('sent','delivered','read','recalled','failed') NOT NULL DEFAULT 'sent' COMMENT '五态：已发送/已送达/已读/已撤回/失败（手册 §5.1 v1.1.2 补列）',
  `read_at`        DATETIME(3)     NULL COMMENT '已读时间，回执用；仅在 status=read 时写入',
  `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（撤回置 status=recalled，硬删仅用于被举报侵权消息）',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_msg` (`client_msg_id`,`to_user_id`),
  KEY `idx_pair` (`from_user_id`,`to_user_id`,`id`),
  KEY `idx_to_status` (`to_user_id`,`status`,`id`),
  KEY `idx_risk` (`risk_level`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内私信（需求 §7.2 #15）';

SET FOREIGN_KEY_CHECKS = 1;
