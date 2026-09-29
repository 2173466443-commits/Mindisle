package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 敏感词分组 sensitive_word_group（需求 §18.3 七类 · 任务 T6.2）。
 *
 * <p><b>级别与动作挂在组上而不是词条上</b>，这正是需求 §18.3 那条结论的落地：
 * 自伤类必须 {@code level=risk, action=TAG}——内容照常放行、只打标记并触发 L2/L3 工单，
 * 因为「删除等于把人推回沉默」。如果 level/action 逐条存在词条上，
 * 任何人加一个自伤词都可能顺手选成 BLOCK，把这条最要紧的合规规则变成手滑的牺牲品。
 * 放在组上之后，「这个词会怎么被处置」由组的 owner 一次定死。</p>
 *
 * <p>{@code word_cnt} 是冗余统计列，口径同 {@code post.comment_cnt}：
 * 由 {@code ConfigAdminService} 在词库变更后<b>按真相表重算覆盖</b>，不做 +1 累加。</p>
 */
@Data
@TableName("sensitive_word_group")
public class SensitiveWordGroup {

  /** level 取值：黑词=拒绝。 */
  public static final String LEVEL_BLACK = "black";
  /** 灰词=转人工。 */
  public static final String LEVEL_GREY = "grey";
  /** 风险词=放行但标记并触发危机链路（自伤自杀类固定这一档）。 */
  public static final String LEVEL_RISK = "risk";

  /** action 取值：拦截。 */
  public static final String ACTION_BLOCK = "BLOCK";
  /** 转人工审核。 */
  public static final String ACTION_REVIEW = "REVIEW";
  /** 放行但打风险标记。 */
  public static final String ACTION_TAG = "TAG";
  /** 只记录不处置。 */
  public static final String ACTION_IGNORE = "IGNORE";

  /** hit_scope 取值：只作用于用户输入。 */
  public static final String SCOPE_USER = "user";
  /** 只作用于模型输出（医疗越界词，BR8）。 */
  public static final String SCOPE_AI = "ai";
  /** 双侧。 */
  public static final String SCOPE_BOTH = "both";

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 类目名，唯一键。七类原文见需求 §18.3。 */
  private String name;

  /** black / grey / risk。 */
  private String level;

  /** BLOCK / REVIEW / TAG / IGNORE。 */
  private String action;

  /** user / ai / both。 */
  private String hitScope;

  /** 组内启用词条数，重算覆盖写。 */
  private Integer wordCnt;

  private String remark;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}