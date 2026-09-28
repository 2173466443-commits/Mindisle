package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 行为埋点表 user_action（需求 §7.2 #9 · 手册任务 T3.10 · 推荐算法唯一的原料来源）。
 *
 * <p><b>这张表只有一处写入方</b>：{@link com.mindisle.track.UserActionRecorder}。
 * 之所以要把「唯一」写成一条硬规矩，是因为埋点的价值全在口径一致——协同过滤拿 weight 打分，
 * 如果点赞在 A 处记 3、在 B 处记 1，模型学到的不是「用户喜欢」而是「哪个入口写的代码」，
 * 而这种错在离线指标上看不出任何异常（§14 第 31 条）。要新增一类行为，
 * 请改 {@link com.mindisle.track.UserActionCatalog} 的表，不要在调用方自己填 weight。</p>
 *
 * <p><b>weight 的取值口径以需求 FR5.1 原文为准，不以 sql/05 的列注释为准。</b>两处原本不一致：
 * FR5.1 写「停留≥3s 计 1 分、点赞 3、收藏 5、评论 4、完读 2、举报 -5、不感兴趣 -3、关注 +5」，
 * 而建表注释写「浏览1/点赞3/收藏5/看完4/不喜欢-5」——把「评论 4」错记成了「看完 4」、
 * 把「举报 -5」错记成了「不喜欢 -5」。需求文档是上游真值，DDL 注释只是它的一句摘要，
 * 故这里取 FR5.1，并把差异写进 dev-log 与本类注释（§14）。列宽 DECIMAL(4,2) 容得下全部取值。</p>
 *
 * <p><b>mood_valence 的口径</b>：DDL 注释说「-5..+5」，而 emotion_record 的效价列只有 -1/0/1，
 * 直接抄过来这张表会永远是 -1/0/1，那五档量程是摆设。本项目的定义是
 * <b>当日最近一次主动打卡的 valence × intensity</b>（强度 1–5，见需求 FR3.1 的五星自评），
 * 乘积恰好落在 -5..+5，且「强度」这个信息只在打卡里有——被动识别出来的 intensity 是模型给的，
 * 不该拿来给用户当时的心情定价。取不到打卡就是 NULL，NULL 表示「未采集」而不是「心情为零」，
 * 这个区别在阶段 7 的情绪感知加权里是判据而非细节。</p>
 */
@Data
@TableName("user_action")
public class UserAction {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 行为发起人，只来自 JWT。 */
  private Long userId;

  /** post / comment / topic / user / message，与 DDL 的 ENUM 逐字一致（小写）。 */
  private String targetType;

  /** 目标 id，随 targetType 解释；target_type=message 时它就是 chat_message.id。 */
  private Long targetId;

  /** 十种行为之一，取值见 {@link com.mindisle.track.UserActionCatalog#ACTION_WEIGHTS}。 */
  private String actionType;

  /** 行为权重，来自 {@link com.mindisle.track.UserActionCatalog}，调用方不得自填。 */
  private BigDecimal weight;

  /** 动作发生时的心情效价 -5..+5，NULL=当日无打卡（不等于中性）。 */
  private Integer moodValence;

  /** 手册 T4.19：ai_feedback 时冗余存一份消息 id，便于与 chat_message 直接联表。 */
  private Long messageId;

  /** 同日幂等桶，uk_action 的一部分；语义是「这一天这一类行为只有一行」。 */
  private LocalDate dayBucket;

  /** feed / detail / search 等来源场景，区分曝光与主动浏览（手册 T7.16）。 */
  private String scene;

  /** 停留时长毫秒，完读与负反馈判定用；同日重复进入取最大值（见 UserActionMapper#upsert）。 */
  private Integer durationMs;

  /** 逻辑删除：撤销点赞/收藏时置 1，行保留作行为留痕（需求 §12 规范 5）。 */
  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  /** 表里没有 updated_at 列，故本类不映射它：MyBatis-Plus 会把它拼进 SQL 并报未知列。 */
}
