package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内私信 private_message（任务 T5.3 · 需求 FR6.1–FR6.6、§7.2 #15 · sql/06_pm.sql 第 22 表）。
 *
 * <p><b>同表双角色，不分收发两张表</b>：一行就是一条消息，{@code from_user_id}/{@code to_user_id}
 * 各指一个人，会话是「这两个 id 的所有行的并集」。DDL 注释里那句「同表双角色」是这个决定的出处，
 * 索引 {@code idx_pair(from,to,id)} 只覆盖一个方向，所以读会话必须两条范围查询 UNION
 * （见 {@link com.mindisle.mapper.PrivateMessageMapper#pageThread}），别以为一条 WHERE 就走完了。</p>
 *
 * <p><b>{@code status} 的五个值里本阶段只写四个</b>：sent / delivered / read / failed 有真实写入方，
 * {@code recalled}（撤回）需求 FR6.4 排在 V1 之外——撤回一条对方可能已经读到的消息，
 * 产品语义上要么真删（那就是被举报侵权的硬删口径）、要么只是「本地隐藏」，
 * 两种都要先把「对方屏幕上那句话到底还在不在」讲清楚才敢做。ENUM 一次给全和阶段 0 的其他表一样，
 * <b>不代表功能已存在</b>，读代码按「谁真的往里写」理解。</p>
 *
 * <p><b>{@code client_msg_id} 的幂等键是 (client_msg_id, to_user_id) 两列</b>（{@code uk_pair_msg}），
 * 不是 client_msg_id 单列。差别在极端情况下会咬人：同一个客户端 UUID 发给两个不同的人，
 * 在这张表里是<b>两条合法消息</b>而不是重复。V1 前端一个 UUID 只发给一个人，所以观察不到差异；
 * 但「重试去重」的判据因此必须连接收方一起比，写断言时别只按 client_msg_id 数行数。</p>
 *
 * <p><b>{@code risk_level} 每行都有值</b>（默认 L0），因为私信也要过危机级联（FR6.6）。
 * 走的是词面 DFA 通道，LLM 通道默认关——理由写在
 * {@link com.mindisle.config.MindisleProperties.Pm} 上，那是本项目最贵的一行配置。</p>
 */
@Data
@TableName("private_message")
public class PrivateMessage {

  /** msg_type 取值，与 DDL 的 ENUM 逐字一致。 */
  public static final String TYPE_TEXT = "text";
  public static final String TYPE_IMAGE = "image";
  public static final String TYPE_SYSTEM = "system";

  /** status 取值，与 DDL 的 ENUM 逐字一致（recalled 见类注释：本阶段无写入方）。 */
  public static final String STATUS_SENT = "sent";
  public static final String STATUS_DELIVERED = "delivered";
  public static final String STATUS_READ = "read";
  public static final String STATUS_FAILED = "failed";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 发送方，逻辑外键 user.id。<b>服务端只认 JWT，不认请求体里的这个字段</b>（见 PmService#send）。 */
  private Long fromUserId;

  /** 接收方，逻辑外键 user.id。 */
  private Long toUserId;

  /** 客户端幂等 ID（UUID，DDL 列宽 64）；撞 uk_pair_msg 即视为重发，不新增行、不重复推送。 */
  private String clientMsgId;

  /** text / image / system。 */
  private String msgType;

  /** 文本内容或图片 URL，入库前过 DFA 与 XSS 净化；列宽 2000，业务上限另有配置。 */
  private String content;

  /** L0–L3，私信同样过危机级联（需求 §5.2、FR6.6）。 */
  private String riskLevel;

  /** sent / delivered / read / failed（recalled 见类注释）。 */
  private String status;

  /** 已读时间，只在 status=read 时写入；未读行必须是 NULL，红点与「对方读到了」都靠它。 */
  private LocalDateTime readAt;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
