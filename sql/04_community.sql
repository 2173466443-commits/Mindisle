-- =============================================================================
-- 心屿 MindIsle · sql/04_community.sql · 社区域（7 张物理表）
-- 依据：需求 §7.2 #4 #5 #6 #7 #8 · ER 文档 §2.4 §3 · 手册 §5.1（post 核心列 DDL 原样抄用 + v1.1.2 补列
--       floor_no / is_top / is_featured / recommend 无关；本文件另补 report_cnt、last_edit_at）
-- =============================================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- 12. post 帖子（需求 §7.2 #4；status 8 态状态机见手册 §5.1 与 FR4.1）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id（匿名帖也存真实发起人，映射见 anonymous_alias）',
  `floor_no`         INT UNSIGNED    NULL COMMENT '手册 §5.1 v1.1.2 补列：树洞连续楼层号，全局递增，便于引用与排错',
  `type`             ENUM('normal','hole','help') NOT NULL DEFAULT 'normal' COMMENT '普通/树洞/求助（FR1 FR4 FR5）',
  `title`            VARCHAR(100)    NOT NULL COMMENT '标题，全文检索列',
  `content`          MEDIUMTEXT      NOT NULL COMMENT '正文，全文检索列，存净化后 Markdown',
  `visibility`       ENUM('public','private','friends') NOT NULL DEFAULT 'public' COMMENT '可见范围（BR8）',
  `is_anonymous`     TINYINT         NOT NULL DEFAULT 0 COMMENT '是否匿名发布',
  `alias_id`         BIGINT UNSIGNED NULL COMMENT '逻辑外键 anonymous_alias.id，匿名帖必填',
  `status`           ENUM('DRAFT','MACHINE_REVIEW','HUMAN_REVIEW','PUBLISHED','REJECTED','APPEALING','TAKEDOWN','DELETED') NOT NULL DEFAULT 'DRAFT' COMMENT '8 态状态机，每次流转写 post_status_log（BR10）',
  `view_cnt`         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '浏览数（Redis 计数 5 分钟回写 BR2）',
  `like_cnt`         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数，真相在 post_like',
  `comment_cnt`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '评论数',
  `collect_cnt`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '收藏数',
  `report_cnt`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '被举报次数，达阈值自动转 HUMAN_REVIEW（FR4.4）',
  `emotion_primary`  VARCHAR(16)     NULL COMMENT '主情绪标签（FR3.2 被动识别）',
  `emotion_score`    DECIMAL(4,3)    NULL COMMENT '情绪强度或置信度 0.000-1.000',
  `risk_level`       ENUM('L0','L1','L2','L3') NOT NULL DEFAULT 'L0' COMMENT '风险等级，L2/L3 建危机工单（FR5.1）',
  `quality_score`    DECIMAL(6,4)    NOT NULL DEFAULT 0 COMMENT '质量分，热度排序与探索位用（需求 §6.2）',
  `is_top`           TINYINT         NOT NULL DEFAULT 0 COMMENT '手册 §5.1 v1.1.2 补列：官方置顶',
  `is_featured`      TINYINT         NOT NULL DEFAULT 0 COMMENT '手册 §5.1 v1.1.2 补列：加精',
  `auto_destroy_at`  DATETIME(3)     NULL COMMENT '树洞定时销毁时间（FR1.5），到期置 DELETED',
  `published_at`     DATETIME(3)     NULL COMMENT '过审发布时间，feed 排序键',
  `last_edit_at`     DATETIME(3)     NULL COMMENT '最后编辑时间，编辑后需重审（BR11）',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_pub` (`status`,`published_at`),
  KEY `idx_user_status` (`user_id`,`status`),
  KEY `idx_emotion` (`emotion_primary`),
  KEY `idx_risk` (`risk_level`,`created_at`),
  KEY `idx_type_pub` (`type`,`published_at`),
  KEY `idx_destroy` (`auto_destroy_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子（需求 §7.2 #4）';

-- -----------------------------------------------------------------------------
-- 13. post_image 配图（需求 §7.2 #5；hash 秒传去重）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_image` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 post.id',
  `url`         VARCHAR(255)    NOT NULL COMMENT '对象存储或本地 uploads 相对路径',
  `sort`        TINYINT         NOT NULL DEFAULT 0 COMMENT '展示顺序，0 起',
  `width`       SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '像素宽，前端占位防抖动',
  `height`      SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '像素高',
  `hash`        CHAR(32)        NOT NULL DEFAULT '' COMMENT '文件 MD5，秒传与重复上传去重',
  `deleted`     TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_sort` (`post_id`,`sort`),
  KEY `idx_hash` (`hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子配图（需求 §7.2 #5）';

-- -----------------------------------------------------------------------------
-- 14. post_status_log 状态机流转留痕（手册 §5.1 施工补列；BR10 要求可追溯）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_status_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`       BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 post.id',
  `from_status`   VARCHAR(16)     NOT NULL COMMENT '变更前状态',
  `to_status`     VARCHAR(16)     NOT NULL COMMENT '变更后状态',
  `operator_id`   BIGINT UNSIGNED NULL COMMENT '操作人，系统流转为空并在 reason 标 system',
  `reason`        VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '变更原因，人工处置必填（BR10）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_time` (`post_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子状态流转留痕（BR10）';

-- -----------------------------------------------------------------------------
-- 15. topic 话题（需求 §7.2 #6；desc 是保留字，列名用 desc_txt）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `topic` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`          VARCHAR(32)     NOT NULL COMMENT '话题名，唯一即规则 uk_name',
  `desc_txt`      VARCHAR(200)    NOT NULL DEFAULT '' COMMENT '话题简介（原字段语义 desc，避开保留字）',
  `cover`         VARCHAR(255)    NULL COMMENT '封面图 URL',
  `audit_status`  ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'APPROVED' COMMENT '新建话题需审核（FR1.7）',
  `post_cnt`      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '帖子数',
  `follow_cnt`    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '关注数',
  `hot_score`     DECIMAL(10,4)   NOT NULL DEFAULT 0 COMMENT '话题热度，定时重算（需求 §6.2）',
  `is_official`   TINYINT         NOT NULL DEFAULT 0 COMMENT '是否官方话题',
  `deleted`       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_hot` (`hot_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话题（需求 §7.2 #6）';

-- -----------------------------------------------------------------------------
-- 16. post_topic 帖-话题多对多（需求 §7.2 #7）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_topic` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 post.id',
  `topic_id`    BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 topic.id',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_topic` (`post_id`,`topic_id`),
  KEY `idx_topic` (`topic_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子话题关联（需求 §7.2 #7）';

-- -----------------------------------------------------------------------------
-- 17. comment 评论（需求 §7.2 #8；parent_id 自关联实现楼中楼，root_id 冗余定楼层）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `post_id`            BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 post.id',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '逻辑外键 user.id',
  `parent_id`          BIGINT UNSIGNED NULL COMMENT '父评论 id，楼中楼；空为一级评论',
  `root_id`            BIGINT UNSIGNED NULL COMMENT '所属一级评论 id，冗余以便整棵子树查询',
  `reply_to_user_id`   BIGINT UNSIGNED NULL COMMENT '被回复用户，用于通知与 @ 展示',
  `content`            VARCHAR(1000)   NOT NULL COMMENT '评论内容，同样过内容安全链',
  `like_cnt`           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '点赞数，真相在 post_like',
  `is_anonymous`       TINYINT         NOT NULL DEFAULT 0 COMMENT '是否匿名评论',
  `alias_id`           BIGINT UNSIGNED NULL COMMENT '逻辑外键 anonymous_alias.id',
  `emotion_primary`    VARCHAR(16)     NULL COMMENT '评论情绪标签，用于温暖评论排序（FR4.3）',
  `status`             ENUM('PENDING','PUBLISHED','REJECTED','DELETED') NOT NULL DEFAULT 'PUBLISHED' COMMENT '审核态',
  `deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_post_status` (`post_id`,`status`,`id`),
  KEY `idx_root` (`root_id`),
  KEY `idx_user` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论与楼中楼（需求 §7.2 #8）';

-- -----------------------------------------------------------------------------
-- 18. post_like 点赞/收藏幂等表（手册 §5.1 施工补；uk_action 即 BR2 幂等规则）
--     ER 文档 §5 取舍 1：点赞与收藏合表，用 action_type 区分，不拆两张表。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post_like` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '行为发起人',
  `target_type`  ENUM('post','comment') NOT NULL COMMENT '目标类型',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '目标 id',
  `action_type`  ENUM('LIKE','COLLECT') NOT NULL DEFAULT 'LIKE' COMMENT '点赞或收藏',
  `day_bucket`   DATE            NOT NULL COMMENT '同日幂等桶；取消行为写 deleted=1 而非物理删',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除，用于取消点赞/收藏',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_action` (`user_id`,`target_type`,`target_id`,`action_type`,`day_bucket`),
  KEY `idx_target` (`target_type`,`target_id`,`action_type`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点赞收藏幂等表（BR2）';

SET FOREIGN_KEY_CHECKS = 1;
