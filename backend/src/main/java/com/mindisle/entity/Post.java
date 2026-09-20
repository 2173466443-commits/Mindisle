package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 帖子 post（需求 §7.2 #4、FR4.1、FR4.2 · 手册 §5.1 表 12 · 状态机见任务 3.3）。
 *
 * <p>与 {@code User} 同样的取舍：status / type / visibility / risk_level 在 MySQL 侧是 ENUM，
 * 这里刻意用 String 而不是 Java enum，避免 MybatisEnumTypeHandler 未注册时把未知值读成 null，
 * 也让运营新增状态不必改代码发版。取值口径集中在 {@code PostService} 的常量里。</p>
 *
 * <p>{@code emotionPrimary} 与 {@code emotionScore} 由阶段 4 的情绪识别回填，
 * 发帖时恒为 null（需求 FR3.2 是「被动识别」，不属于发布链路的前置条件）。</p>
 */
@Data
@TableName("post")
public class Post {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 user.id：匿名帖也必须存真实发起人，否则危机工单找不到人（FR10.5）。 */
  private Long userId;

  /** 树洞连续楼层号，全局递增（手册 §5.1 v1.1.2 补列），普通帖为 null。 */
  private Integer floorNo;

  /** normal 普通帖 / hole 树洞 / help 求助帖（FR4.2）。 */
  private String type;

  /** 标题，FR4.1 限 50 字，列宽留到 100 是给后台运营改标题留余量。 */
  private String title;

  /** 正文，存纯文本 + 换行 + emoji（FR4.1），渲染转义由前端负责。 */
  private String content;

  /** public 公开 / private 仅自己（FR4.1）；DDL 里的 friends 属 FR4.6 好友可见，暂未开放。 */
  private String visibility;

  /** 是否匿名发布（FR1.4），树洞恒为 1。 */
  private Integer isAnonymous;

  /** 逻辑外键 anonymous_alias.id，匿名帖必填：展示名靠它，不存昵称副本，改名不用回刷帖子。 */
  private Long aliasId;

  /** 8 态状态机当前值，每次流转写 post_status_log（BR10）。 */
  private String status;

  private Integer viewCnt;

  private Integer likeCnt;

  private Integer commentCnt;

  private Integer collectCnt;

  /** 被举报次数，达阈值自动转 HUMAN_REVIEW（FR4.4，计数在任务 3.11）。 */
  private Integer reportCnt;

  /** 主情绪标签（任务 4.2 回填）。 */
  private String emotionPrimary;

  /** 情绪强度 0.000-1.000（任务 4.2 回填）。 */
  private BigDecimal emotionScore;

  /** L0-L3 危机等级（需求 §5.2），L2/L3 建 alert_ticket（创新点 3）。 */
  private String riskLevel;

  /** 质量分，热度排序与探索位用（需求 §6.2），阶段 7 回填。 */
  private BigDecimal qualityScore;

  private Integer isTop;

  private Integer isFeatured;

  /** 树洞定时销毁时间（FR4.2），发帖时算好落库，不在查询时现算（手册 §6.1 行 836）。 */
  private LocalDateTime autoDestroyAt;

  /** 过审发布时间，feed 排序键；未发布恒为 null。 */
  private LocalDateTime publishedAt;

  /** 最后编辑时间，编辑后需重审（BR11，编辑接口在任务 3.15）。 */
  private LocalDateTime lastEditAt;

  /** 逻辑删除（删除与下架都只置这一位，证据链要留）。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
