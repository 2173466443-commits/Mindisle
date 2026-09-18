-- =============================================================================
-- 心屿 MindIsle · sql/05_recommend.sql · 推荐域（3 张物理表）
-- 依据：需求 §7.2 #9 #21 #22 · 需求 §6.2 推荐算法设计 · ER 文档 §2.5 §3（19-21）
--       · 手册 §5.1 v1.1.2 补列（recommend_result.position / is_exposed / exposed_at）
--       · 手册 T4.19（AI 消息反馈并入 user_action：action_type=ai_feedback + message_id）
--       · 手册 T7.7（可解释推荐 → reason）· T7.16（曝光去重 → expose + day_bucket）
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 19. user_action 用户行为埋点（需求 §7.2 #9；协同过滤唯一数据源，创新点②依赖 mood_valence）
--     需求 §7.2 索引要求原文：「user_action(user_id,created_at)、user_action(target_type,target_id)」
--     幂等口径与 post_like 一致：同用户+同目标+同动作+同日只记一条（day_bucket 分桶）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_action` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id（需求 §7.2 #9）',
  `target_type`    ENUM('post','comment','topic','user') NOT NULL COMMENT '目标类型：帖子/评论/话题/用户（需求 §7.2 #9）',
  `target_id`      BIGINT UNSIGNED NOT NULL COMMENT '目标逻辑主键，随 target_type 解释，不做物理外键（ER §4）',
  `action_type`    ENUM('view','like','collect','comment','read_through','dislike','follow','report','expose','ai_feedback') NOT NULL COMMENT '十种行为：需求 §7.2 #9 九种 + 手册 T4.19 第 10 种 ai_feedback',
  `weight`         DECIMAL(4,2)    NOT NULL DEFAULT 1.00 COMMENT '行为权重（浏览1/点赞3/收藏5/看完4/不喜欢-5），CF 打分输入（需求 §6.2）',
  `mood_valence`   TINYINT         NULL COMMENT '创新点②专用：动作发生时用户心情效价 -5..+5，NULL=未采集（情绪感知加权协同过滤）',
  `message_id`     BIGINT UNSIGNED NULL COMMENT '手册 T4.19：action_type=ai_feedback 时存 chat_message.id，记录对 AI 回复的赞/踩',
  `day_bucket`     DATE            NOT NULL COMMENT '日期分桶，配合 uk_action 实现同日同动作幂等，防止重复点击打爆行为表',
  `scene`          VARCHAR(16)     NULL COMMENT '行为场景 feed/detail/search，用于区分曝光与主动浏览（手册 T7.16）',
  `duration_ms`    INT UNSIGNED    NULL COMMENT '停留时长毫秒，读完率与负反馈判定用',
  `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（用户撤销行为时软删）',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行为发生时间，CF 时间衰减用（需求 §6.2）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action` (`user_id`,`target_type`,`target_id`,`action_type`,`day_bucket`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_target` (`target_type`,`target_id`,`action_type`),
  KEY `idx_mood` (`mood_valence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户行为埋点（需求 §7.2 #9）';

-- -----------------------------------------------------------------------------
-- 20. item_similarity ItemCF 相似度缓存（需求 §7.2 #22；手册 §7.5「离线算 Top-K 邻居，在线只查表」）
--     注意：本表以 item_id 为主键，无自增 id（ER §3 第 20 行「主键 item_id」）
--     手册 §5.1 DDL 规范 2：嵌套结构用原生 json 列，不拆子表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `item_similarity` (
  `item_id`      BIGINT UNSIGNED NOT NULL COMMENT '主键且逻辑外键 post.id（一帖一行，ER §3 第 20 行）',
  `sim_items`    JSON            NOT NULL COMMENT 'Top-K 邻居数组 [{"item":101,"score":0.83},...]，K 默认 200（sys_config rec.topk_neighbor）',
  `neighbor_cnt` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '实际邻居数量，冷启动为 0 时该帖不参与 ItemCF 召回',
  `calc_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后一次离线计算时间，过久视为过期需重算',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除（帖子下架时同步失效）',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ItemCF 相似度缓存（需求 §7.2 #22）';

-- -----------------------------------------------------------------------------
-- 21. recommend_result 推荐结果缓存（需求 §7.2 #21；手册 §7.5「预计算 + 六路召回 + 可解释」）
--     需求 §7.2 索引要求原文：「recommend_result(user_id,scene,position)」
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `recommend_result` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id（需求 §7.2 #21）',
  `scene`          ENUM('feed','related') NOT NULL COMMENT '场景：首页信息流 / 相关推荐（需求 §6.2 两处入口）',
  `item_id`        BIGINT UNSIGNED NOT NULL COMMENT '被推荐的帖子，逻辑外键 post.id',
  `score`          DECIMAL(8,6)    NOT NULL DEFAULT 0 COMMENT '综合打分 0-1，越靠前越大（需求 §6.2 加权融合结果）',
  `recall_channel` ENUM('usercf','itemcf','content','hot','explore','emotion') NOT NULL COMMENT '六路召回：UserCF/ItemCF/内容/热度/探索/情绪感知（创新点②走 emotion）',
  `reason`         VARCHAR(200)    NULL COMMENT '可解释推荐文案（手册 T7.7），如「因为你常看失眠话题且今天心情偏低落」',
  `mode`           ENUM('cf','hot','ab') NOT NULL DEFAULT 'cf' COMMENT '该批次生成模式：CF / 纯热度 / AB 对照（需求 §6.4 消融实验需要）',
  `position`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '手册 §5.1 v1.1.2 补列：组内排序位置，从 0 开始，分页直接按此取',
  `is_exposed`     TINYINT         NOT NULL DEFAULT 0 COMMENT '手册 §5.1 v1.1.2 补列：是否已曝光，曝光后写 user_action(expose)',
  `exposed_at`     DATETIME(3)     NULL COMMENT '首次曝光时间，配合 rec.expose_dedup_days 做 N 天内不重复推荐',
  `calc_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '本批次计算时间，超 TTL 重新预计算',
  `deleted`        TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_scene_item_mode` (`user_id`,`scene`,`item_id`,`mode`),
  KEY `idx_user_scene_pos` (`user_id`,`scene`,`position`),
  KEY `idx_channel` (`recall_channel`,`score`),
  KEY `idx_calc_at` (`calc_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='推荐结果缓存（需求 §7.2 #21）';

SET FOREIGN_KEY_CHECKS = 1;
