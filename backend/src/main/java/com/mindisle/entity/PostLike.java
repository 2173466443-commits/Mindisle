package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 点赞/收藏幂等表 post_like（任务 3.6 · 需求 BR2、FR4.4 · 手册 §5.1 表 18）。
 *
 * <p><b>本行「存在」才代表「赞过」，取消不物理删而是 deleted=1</b>：
 * 需求 §12 规范 5 要的是行为可回溯，物理删会让「谁在什么时间赞过又取消」变成查无实据。
 * 因此计数口径统一为 {@code COUNT(DISTINCT user_id) WHERE deleted=0}，
 * 而不是数行数——见 {@link com.mindisle.mapper.PostLikeMapper#countActiveUsers} 那段为什么不能数行。</p>
 *
 * <p>{@code targetType} 取 post / comment（与 DDL 的 ENUM 逐字一致）。评论点赞属任务 3.7，
 * 本阶段只有 post 会被写入，但列先留着：表结构定稿在阶段 2，代码不该等到 3.7 才认得 comment。</p>
 *
 * <p>{@code dayBucket} 是 {@code uk_action} 的一部分，字面语义是「同日幂等桶」。
 * 点赞的产品语义是「一人一赞」而不是「一天一赞」，所以写入顺序被固定成
 * <b>先查活动行 → 再复活历史行 → 最后才 INSERT IGNORE</b>（见 PostInteractionService），
 * 正常路径下一个 (用户, 目标, 动作) 永远只有一行；day_bucket 记录的是这一行<b>首次成立</b>的日期。
 * 只在并发跨日的极端情况下才可能出现两行活动行，那正是计数用 DISTINCT user_id 的原因。</p>
 */
@Data
@TableName("post_like")
public class PostLike {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 行为发起人。 */
  private Long userId;

  /** post / comment，与 DDL 的 ENUM 逐字一致。 */
  private String targetType;

  /** 目标 id，随 targetType 解释；不做物理外键（ER §4）。 */
  private Long targetId;

  /** LIKE / COLLECT（DDL ENUM 原文大写），点赞与收藏合表（ER §5 取舍 1）。 */
  private String actionType;

  /** 同日幂等桶；语义见类注释里那条写入顺序。 */
  private LocalDate dayBucket;

  /** 逻辑删除：1 表示「已取消」，行仍保留作行为留痕。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
