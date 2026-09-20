package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 话题 topic（需求 §7.2 #6 · 手册 §5.1 表 15）。
 *
 * <p>注意原字段语义为 desc，建表时改名 desc_txt 以避开 MySQL 保留字。
 */
@Data
@TableName("topic")
public class Topic {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 话题名，唯一索引 uk_name。 */
  private String name;

  /** 话题简介。 */
  private String descTxt;

  /** 封面图 URL。 */
  private String cover;

  /** PENDING / APPROVED / REJECTED，新建话题须审核（FR1.7）。 */
  private String auditStatus;

  private Integer postCnt;

  private Integer followCnt;

  /** 热度，DECIMAL(10,4)，定时任务重算（需求 §6.2）。 */
  private BigDecimal hotScore;

  /** 是否官方话题（官方 + 已过审才进公开话题墙）。 */
  private Integer isOfficial;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
