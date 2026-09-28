package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知 notify_message（任务 T3.11-b · 需求 FR9.1 · 手册 §6.1 行 3.11 · sql/08_config.sql 第 30 表）。
 *
 * <p><b>本表没有「触发者」列</b>，这是读它之前必须先知道的一件事：actor 只能进 {@code title} 文案
 * （「小明 赞了你的帖子」）。两个直接后果：① 幂等判据只能连文案一起比
 * （{@link com.mindisle.notify.NotifyService#shouldSkip}），② 「A、B 等 5 人赞了你」这种同类聚合
 * （FR9.2）在本表结构下要么靠文案前缀匹配、要么改表，阶段 3 不做，已记进手册 §14。</p>
 *
 * <p><b>type 八类里本阶段只写 like / comment / follow 三类</b>：audit 与 report 的文案要等 T6.1 的
 * 处置结论，crisis 走工单通道，pm 属阶段 5，system 属阶段 6。DDL 的 ENUM 一次给全是当时的决定，
 * 不代表功能已存在——读代码的人按「谁真的往里写」来理解这张表才不会被误导。</p>
 *
 * <p><b>它不是「消息盒子」而是「事件回执」</b>：{@code ref_type}/{@code ref_id} 只存跳转指针，
 * 不复制内容，所以原帖被删之后通知仍然能列出、点进去才会 404。这个取舍（不做级联清理、
 * 让通知成为「曾经发生过这件事」的证据）写进 {@link com.mindisle.notify.NotifyService} 的类注释。</p>
 */
@Data
@TableName("notify_message")
public class NotifyMessage {

  /** type 取值，与 DDL 的 ENUM 逐字一致（八个值一个都不改，改这里必须同时改 DDL）。 */
  public static final String TYPE_LIKE = "like";
  public static final String TYPE_COMMENT = "comment";
  public static final String TYPE_FOLLOW = "follow";
  public static final String TYPE_PM = "pm";
  public static final String TYPE_SYSTEM = "system";
  public static final String TYPE_AUDIT = "audit";
  public static final String TYPE_CRISIS = "crisis";
  public static final String TYPE_REPORT = "report";

  /** ref_type 取值（DDL 是 VARCHAR(16)，所以约束只能钉在代码里）。 */
  public static final String REF_POST = "post";
  public static final String REF_COMMENT = "comment";
  public static final String REF_USER = "user";
  public static final String REF_REPORT = "report";
  public static final String REF_CONVERSATION = "conversation";

  /**
   * 私信通知的跳转指针。<b>ref_id 存的是对方用户 id（会话号），不是消息 id</b>：
   * 点通知要落进「和这个人的会话」（U10），而不是某一条消息——私信列表按人对账，
   * 未读数也按人算，跳进单条消息反而看不到上下文。
   * 前端 {@code api/notify.js#notifyRoute} 的 {@code pm -> pm-detail{peerId}} 与本行是一对，
   * 改这一侧必须同时改那一侧，两处注释互相引用。</p>
   */
  public static final String REF_PM = "pm";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 接收人，逻辑外键 user.id。写通知时取的是「内容作者 / 被关注者」，只来自库里的行。 */
  private Long userId;

  /** 八类之一，见上面的常量。 */
  private String type;

  /** 列表页主文案，DDL 列宽 100；「谁干了什么」全在这一句里。 */
  private String title;

  /** 辅助行（被赞的帖子标题、评论原文摘录），DDL 列宽 500，本端写入前按码点截断。 */
  private String content;

  /** 跳转对象类型，前端据此决定点击行为。 */
  private String refType;

  /** 跳转对象主键。 */
  private Long refId;

  /** 0 未读 / 1 已读；红点只数这个组合（走 idx_user_read 覆盖索引）。 */
  private Integer isRead;

  /** 已读时间，与 is_read 同时写；写通知时留 null 交给 DDL 默认。 */
  private LocalDateTime readAt;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
