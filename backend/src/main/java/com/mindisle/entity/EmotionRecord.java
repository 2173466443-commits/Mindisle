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
 * 情绪记录 emotion_record（sql/03_emotion.sql 表 10 + sql/14 的 sleep_bucket · 需求 §7.2 #14）。
 *
 * <p><b>主动打卡与被动识别共用这一张表</b>，用 {@code source} 分：checkin（用户在 U3 填的）、
 * chat（对话里词典/LLM 识别出来的）、post（发帖被动识别）。这是需求 FR3.1/FR3.2 的直接翻译：
 * 档案页要的是「这个人今天的情绪」，不关心它是问出来的还是看出来的；
 * 但统计时必须能分开，否则「打卡率」和「识别覆盖率」两个完全不同的指标会被混成一个数。</p>
 *
 * <p>{@code confidence} 是 BR12 的执行位：{@code < 0.6} 的记录计入 {@code uncertain}、
 * 不进趋势线（需求 FR3.2）。所以这一列不是给论文看的装饰，它是档案页 SQL 的过滤条件，
 * 写在 {@code EmotionRecordMapper#dailyTrend} 里。</p>
 *
 * <p>{@code recordDate} 单独存一列而不是每次从 {@code createdAt} 现算，是为了让
 * 「按天聚合」能直接命中 {@code idx_user_date}；时区口径固定 Asia/Shanghai
 * （application.yml 的 jackson.time-zone），换账号跨时区不会把一天的记录劈成两天。</p>
 *
 * <p>{@code sleepBucket} 是 sql/14 补的列，ER 文档里没有：前端打卡表单有「睡得怎么样」四档，
 * 与其静默丢掉用户的输入，不如给它一个家。非打卡来源恒为 null。</p>
 */
@Data
@TableName("emotion_record")
public class EmotionRecord {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  private Long userId;

  /** checkin | chat | post，见类注释。 */
  private String source;

  /** 来源明细 id（chat_message.id / post.id）；打卡为 null。 */
  private Long refId;

  /** 脱敏后截断的触发文本，≤200 列宽。打卡时存用户写的 note，对话时存命中的词面。 */
  private String textSnippet;

  /** joy/trust/anger/sadness/fear/disgust/neutral 之一，取值域由 EmotionPrior.LABELS 钉住。 */
  private String label;

  /** 强度 1-5。打卡由用户自选，被动识别由词典通道给出。 */
  private Integer intensity;

  /** 效价 -1/0/1，不是 -1.0~1.0 的连续值：ER 文档 §5 取舍 2 选了分档，两侧都按分档算。 */
  private Integer valence;

  private BigDecimal confidence;

  /** dict | llm | manual，消融实验分组键。 */
  private String channel;

  /** 词典通道写 {@code dut-4.0-mindisle-v1+prior-v1.0}，LLM 通道写 {@code 模型名@提示词版本}。 */
  private String modelVersion;

  private LocalDate recordDate;

  /** 0 不足4h / 1 4-6h / 2 6-8h / 3 ≥8h，仅打卡来源有值。 */
  private Integer sleepBucket;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
