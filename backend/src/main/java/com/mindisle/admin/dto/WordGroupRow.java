package com.mindisle.admin.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * A4/A8 展示「组 + 组内词数 + 处置口径」的行（任务 T6.2）。
 *
 * <p>{@code wordCnt} 取的是<b>库里启用词数</b>（实时 COUNT），不是
 * {@code sensitive_word_group.word_cnt} 那个冗余统计列，两个数不一致时以本行为准，
 * 由 {@code ConfigAdminService#rebuildDict} 把统计列重算成它。</p>
 */
@Data
public class WordGroupRow {

  private Long id;

  private String name;

  private String level;

  private String action;

  private String hitScope;

  /** 实时启用词条数。 */
  private Long wordCnt;

  /** 库里存的统计列，用于回答「账本和成品差了多少」。 */
  private Long storedWordCnt;

  private String remark;
}