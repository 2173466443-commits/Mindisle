package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * A8 词库管理列表的一行：词条 + 它所在组的处置口径（手册 §9.2 A8 · 任务 T6.2）。
 *
 * <p>{@code level}/{@code action}/{@code hitScope} 三个字段<b>不来自 sensitive_word</b>，
 * 而是从组上 join 过来的。理由见 {@code SensitiveWordGroup} 的类注释：处置口径挂在组上，
 * 列表把它们摊平展示，管理员才看得见「这个词命中后会怎样」。</p>
 */
@Data
public class DictRow {

  private Long id;

  private Long groupId;

  /** 组名，需求 §18.3 七类之一。 */
  private String groupName;

  /** black / grey / risk。 */
  private String level;

  /** BLOCK / REVIEW / TAG / IGNORE。 */
  private String action;

  /** user / ai / both。 */
  private String hitScope;

  /** contains / regex / whole。 */
  private String matchType;

  private String word;

  private Integer hitCnt;

  /** 1 启用 / 0 停用。 */
  private Integer status;

  /** 该词归一化后的形态（变体预览，手册 §9.1「变体归一化预览」）。 */
  private String normalized;

  /** 当前生效的词库版本号，如 v0.1。 */
  private String dictVersion;
}