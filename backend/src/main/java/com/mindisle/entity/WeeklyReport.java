package com.mindisle.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 情绪周报 weekly_report（sql/03_emotion.sql 表 11 · 手册 T4.20、需求 FR3.5）。
 *
 * <p>{@code uk_user_week(user_id, week_start)} 让「重新生成本周周报」成为一次 UPSERT
 * 而不是插一行：定时任务可以重跑，用户也可以手动点「再算一次」。建单 SQL 见
 * {@code WeeklyReportMapper#upsert}，那里说明了为什么用
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是「先查再写」。</p>
 *
 * <p>{@code insight} 在 MySQL 侧是 JSON 列，这里刻意映射成 <b>String</b> 而不是
 * {@code Map}/{@code List}：本项目的 mybatis-plus 没有注册 JSON typeHandler
 * （{@code MybatisPlusConfig} 只配了分页与枚举处理器），硬接一个对象会在读的时候
 * 报「cannot determine uncategorized JdbcType」。<b>序列化/反序列化收在
 * {@code WeeklyReportService} 一处</b>，读写都走它，界面上不直接暴露这个字符串。
 * 这个取舍写进 dev-log，不改 DDL —— 改列类型会让阶段 6 的图表脚本跟着遭殃。</p>
 *
 * <p>{@code generator} 只有 llm / template 两值：拿不到模型或超预算时走纯统计模板，
 * 周报仍然要能出（需求 FR3.5 是「稳定产出」，不是「必须有 AI 文案」），
 * 但界面上要如实标出生成方式，不能把模板冒充 LLM 文案。</p>
 */
@Data
@TableName("weekly_report")
public class WeeklyReport {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  private Long userId;

  /** 周一，ISO 周口径。 */
  private LocalDate weekStart;

  private LocalDate weekEnd;

  /** 本周打过卡的天数（FR3.1 每日一次的计数）。 */
  private Integer checkinDays;

  /** 本周情绪记录总条数（含被动识别）。 */
  private Integer recordCnt;

  private String dominantLabel;

  private BigDecimal avgIntensity;

  /** 正向占比 0.000-1.000：valence>0 的条数 / 总条数。 */
  private BigDecimal positiveRatio;

  /** 与上一周的 avg_intensity 差，负数表示强度上升还是下降要看符号定义，统一按「本周-上周」，写在服务层。 */
  private BigDecimal trendDelta;

  /** JSON 列，实体里是字符串，理由见类注释。 */
  private String insight;

  private String summaryText;

  /** llm | template。 */
  private String generator;

  /** 1 = 用户已选择去标识分享（FR3.5、BR13）。 */
  private Integer sharedFlag;

  /**
   * 去标识分享生成的帖子 id（T4.20 ③）。非空即「这份周报已经分享过一次」，
   * 再点分享直接返回它而不是再发一条 —— 见 sql/16_weekly_share.sql 的头注。
   */
  private Long sharedPostId;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
