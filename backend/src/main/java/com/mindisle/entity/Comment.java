package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评论 comment（任务 3.7 · 需求 FR4.4「一级 + 楼中楼，≤1000 字」、BR4、BR6 · 手册 §5.1 表 17）。
 *
 * <p><b>两级是展示口径，不是存储口径</b>：手册 §6.1 行 3.7 写的是「{@code parent_id} 两级」，
 * 而楼中楼在真实社区里必然出现「回复某人的回复」。这里的存法是
 * {@code parent_id} 指向<b>被回复的那一条</b>（谁被 @ 了就指谁，可以是很深的一条回复），
 * {@code root_id} 指向<b>所属一级评论</b>（回复一级评论时等于自身 id 的父级，即 root_id = 父 id）。
 * 于是「整棵子树」是一次 {@code WHERE root_id = ?} 就能捞全的平面集合，
 * 展示时永远只有「一级 + 一堆平铺回复」两层，不会出现无限缩进把正文挤成一条缝。
 * 为什么不用闭包表/路径枚举：评论的子树深度天然被钉死为 2，多出来的表与递归代价一分钱收益都没有。</p>
 *
 * <p>{@code status} 与 post 的八态<b>不是一套枚举</b>（DDL 原文四态 PENDING/PUBLISHED/REJECTED/DELETED）：
 * 评论没有「草稿」「人审中/机审中」的区分，也不需要发帖那样的状态流转日志表，
 * 所以机审结论直接落进终态，一次 INSERT 写完。对应关系见
 * {@link com.mindisle.post.CommentService#statusFor}。</p>
 *
 * <p>{@code emotionPrimary} 由阶段 4 的情绪识别回填（评论情绪是「温暖评论排序」FR4.3 的输入），
 * 本阶段恒为 null —— 留空不等于漏做，列先建好是阶段 2 定 schema 的职责。</p>
 */
@Data
@TableName("comment")
public class Comment {

  /** 一级评论（parent_id 与 root_id 都为空）。 */
  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_PUBLISHED = "PUBLISHED";
  public static final String STATUS_REJECTED = "REJECTED";
  public static final String STATUS_DELETED = "DELETED";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 所评帖子，逻辑外键 post.id。 */
  private Long postId;

  /** 评论作者。 */
  private Long userId;

  /** 被回复的那一条评论 id；一级评论为 null。 */
  private Long parentId;

  /** 所属一级评论 id；一级评论为 null。整棵子树靠它一次捞全。 */
  private Long rootId;

  /** 被回复的用户，用于「回复 @某某」展示与通知（FR9.1）。 */
  private Long replyToUserId;

  /** 评论内容，与 post 走同一条内容安全链。 */
  private String content;

  /** 评论点赞数；真相在 post_like（target_type='comment'），评论点赞属任务 3.7 之后的增强。 */
  private Integer likeCnt;

  /** 是否匿名评论。与帖子的匿名互相独立：树洞帖下也可以实名评论。 */
  private Integer isAnonymous;

  /** 匿名评论的马甲 id，口径与 post.alias_id 一致（不抄昵称副本）。 */
  private Long aliasId;

  /** 评论情绪标签（阶段 4 回填）。 */
  private String emotionPrimary;

  /** PENDING / PUBLISHED / REJECTED / DELETED，与 DDL 的 ENUM 逐字一致。 */
  private String status;

  /** 逻辑删除：删帖级联隐藏评论（FR4.3）与作者自删都走这一位。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
