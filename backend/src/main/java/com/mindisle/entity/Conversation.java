package com.mindisle.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 会话头 conversation（sql/02_ai.sql 表 1 · 需求 §7.2 #1、FR2.1）。
 *
 * <p><b>style 的三档是 ENUM 而不是自由字符串</b>：warm / rational / humorous，逐字对齐
 * {@code prompts/chat_default_v1.txt} 里「本次会话的人格微调」那三行。前端的四档
 * （gentle/direct/humor/listener）在 {@code ChatService} 里做一次映射，映射表写死在
 * 一处并配单测 —— 因为「前端第四个选项落到哪一档」这件事如果散在三个地方各说一遍，
 * 迟早会出现「同一个用户在不同入口拿到不同人格」。</p>
 *
 * <p><b>summary 是滚动摘要，不是聊天记录</b>：任务 T4.3 要求会话超过 12 轮后把更早的轮次
 * 压成 ≤200 字写在这里（列宽 varchar(500) 是刻意留的余量，压缩提示词本身要求 ≤200 字，
 * 但模型不总是听话，落库前还要再截一次）。有了它，上下文才不必「要么全带、要么全丢」。</p>
 *
 * <p>{@code lastMsgAt} 是会话列表唯一的排序键（任务 T4.2「保留最近 50 会话」）。
 * 它由 {@code ChatService} 在每条消息落库后显式更新，不依赖触发器 —— 数据库里没有触发器，
 * 这条口径在 sql/02_ai.sql 的头注里已经写明。</p>
 */
@Data
@TableName("conversation")
public class Conversation {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 归属用户，逻辑外键 user.id。所有读路径都必须带这个条件，见 ChatService#requireOwned。 */
  private Long userId;

  /** 列表标题，取首条用户消息前 20 字（任务 T4.2），列宽 64 留的是中文余量。 */
  private String title;

  /** warm | rational | humorous，见类注释。 */
  private String style;

  /** 滚动摘要，≤500 字；新会话为 null。 */
  private String summary;

  private LocalDateTime lastMsgAt;

  /** ACTIVE | ARCHIVED | DELETED。列表只查 ACTIVE；ARCHIVED 留给 T4.21 的「不删但收起来」。 */
  private String status;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  /** 列上有 ON UPDATE CURRENT_TIMESTAMP(3)，所以本类从不把它写进 UPDATE 的列清单：
   *  MyBatis-Plus 的默认字段策略是 NOT_NULL，实体里留 null 就不会出现在 INSERT/UPDATE 里，
   *  时间由 MySQL 自己写。带上去的代价是「把查出来的旧值原样回写」，
   *  那会让 updated_at 看上去一直在动，其实一个字都没变。 */
  private LocalDateTime updatedAt;
}
