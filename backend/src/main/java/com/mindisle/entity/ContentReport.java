package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 内容举报 content_report（任务 T3.11 · 需求 FR4.7 · 手册 §6.1 行 3.11 · sql/12_report.sql 第 32 表）。
 *
 * <p><b>这张表是 {@code post.report_cnt} 的真相表</b>，与 post_like 之于 like_cnt、
 * comment 之于 comment_cnt 完全同构：每次写之后按它重算覆盖写冗余列
 * （见 {@link com.mindisle.mapper.PostMapper#refreshReportCnt}），
 * 漂移在结构上不可能发生。为什么不复用 user_action / 只写 audit_task.remark，
 * 三条取舍记在 sql/12_report.sql 末尾，那里是这套判断第一次成型的地方。</p>
 *
 * <p><b>举报不匿名，也不允许「取消举报」</b>：{@code uk_reporter_target} 让同一个人对同一条
 * 内容只有一行，重复提交是幂等而非第二条证据（{@code ReportService} 回 {@code duplicated=true}）。
 * {@code deleted} 位因此只用于测试数据清理——一旦真的置成 1，唯一键仍然占位，
 * 那个人就永远不能再举报同一条内容了；要清就物理删，这条已写进手册 §14。</p>
 *
 * <p>{@code postId} 与 {@code authorId} 是<b>有意冗余</b>：评论举报（{@code target_type='comment'}）
 * 也要能一句话定位宿主帖与责任人，而管理端「按帖聚合举报」是本表最高频的查询，
 * 不该每次都靠 target_type 分支去 JOIN 两张不同的表。</p>
 */
@Data
@TableName("content_report")
public class ContentReport {

  /** target_type 取值，与 DDL 的 ENUM 逐字一致。 */
  public static final String TARGET_POST = "post";
  public static final String TARGET_COMMENT = "comment";

  /** 处置三态，由管理端 T6.1 回填；PENDING 是本服务写入的唯一初值。 */
  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_REJECTED = "REJECTED";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 举报人，逻辑外键 user.id。真实身份，不走马甲。 */
  private Long reporterId;

  /** post / comment。本阶段只写 post。 */
  private String targetType;

  /** 被举报对象主键。 */
  private Long targetId;

  /** 所属帖子主键（评论举报时是宿主帖）。 */
  private Long postId;

  /** 被举报内容的作者，工单与处置直接读。 */
  private Long authorId;

  /** 理由分类，六个字面量见 {@link com.mindisle.post.ReportService#REASONS}。 */
  private String reason;

  /** 补充描述，服务端上限走配置（默认 200 字），列宽 500。 */
  private String description;

  /** 截图证据，逗号分隔的相对路径，最多 3 张。 */
  private String evidenceUrls;

  /** PENDING / ACCEPTED / REJECTED。 */
  private String status;

  private Long handlerId;

  private LocalDateTime handledAt;

  /** 给举报人的处置说明（T6.1 回填后经 notify_message(type=report) 回执）。 */
  private String resultNote;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
