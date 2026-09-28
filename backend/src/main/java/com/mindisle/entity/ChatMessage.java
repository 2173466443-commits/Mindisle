package com.mindisle.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * AI 消息体 chat_message（sql/02_ai.sql 表 2 + sql/14_stage4_alter.sql 的 interrupted · 需求 §7.2 #2）。
 *
 * <p><b>一条消息一行，user 与 assistant 共用这张表</b>：上下文装配（T4.3）要按时间顺序
 * 把「用户说—模型答」成对取出，拆两张表会让「近 8 轮」这种口径变成两次查询再拼序。</p>
 *
 * <p>{@code emotionLabel}/{@code emotionScore}/{@code emotionChannel} 是<b>用户消息</b>上的情绪标注
 * ——助手消息通常为空。这三个字段是级联识别（创新点 1）的落库证据：{@code channel} 取值
 * dict/llm/manual 同时就是消融实验的分组键，没有它，论文里「词典通道 vs LLM 通道」的对比
 * 只能靠回忆。列宽与 {@link EmotionRecord} 的对应列逐字一致，两侧都写 ENUM('dict','llm','manual')，
 * 因为「同一个语义在两张表里叫不同名字」是本项目在 dev-log 里反复记过的坑。</p>
 *
 * <p>{@code interrupted}（sql/14 新增）：任务 T4.6 的「停止生成」会把半截回复也落库并置 1。
 * 它必须是独立的一列，不能靠「content 长度短」推断 —— 短回复是完全正常的输出，
 * 拿长度当判据会统计出一个假的「中断率」。</p>
 *
 * <p>{@code degraded}=1 表示这次回复来自离线共情话术库（T4.13 熔断降级）。
 * 前端把它显示成「离线模式」，论文用它统计可用性。二者都不许被「降级也返 200」掩盖。</p>
 */
@Data
@TableName("chat_message")
public class ChatMessage {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  private Long conversationId;

  /** 冗余存一份归属：所有越权校验都要求「消息 → 会话 → 用户」这条链上的人对得上，见 ChatService。 */
  private Long userId;

  /** user | assistant | system。system 只由服务端写（如危机拦截提示），不接受客户端传入。 */
  private String role;

  private String content;

  private Integer tokensIn;

  private Integer tokensOut;

  /** 实际使用的模型名，来自 ChatResult，不取配置值 —— 配置和真实调用可以不一致（降级、灰度）。 */
  private String model;

  private String promptVersion;

  private String emotionLabel;

  /** DECIMAL(4,3)，0.000-9.999 的列宽装 0-1 的置信度；这里刻意不用 double，浮点会让「conf<0.55」这种判定不稳定。 */
  private BigDecimal emotionScore;

  /** dict | llm | manual，见类注释。 */
  private String emotionChannel;

  /** L0 | L1 | L2 | L3，与 CrisisGrader 的字面量同一套。 */
  private String riskLevel;

  /** NONE | UP | DOWN，任务 T4.19 的反馈按钮写它（本阶段先留列，接口在 T3.10 埋点之后补）。 */
  private String feedback;

  private Integer latencyMs;

  /** 1 = 本次回复来自离线话术库（T4.13）。 */
  private Integer degraded;

  /** 1 = 用户点了停止 / 流被中断，content 只存已生成的部分（T4.6）。sql/14 新增列。 */
  private Integer interrupted;

  @TableLogic
  private Integer deleted;

  /** created_at 有 DEFAULT CURRENT_TIMESTAMP(3)，实体里留 null 交给 MySQL 写（理由见 Conversation.updatedAt）。
   *  读必须读得到它：会话回看按它排序，「近 3 日情绪」也按它归集。 */
  private LocalDateTime createdAt;
}
