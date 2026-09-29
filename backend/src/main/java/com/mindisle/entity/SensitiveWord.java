package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 敏感词词条 sensitive_word（需求 §7.2 #16 后半 · §18.3 · 任务 T6.2 首次有管理端写入入口）。
 *
 * <p><b>这张表是词库的「账本」，{@code dict/*.txt} 快照是「成品」</b>：{@code SensitiveWordEngine}
 * 启动时读的是快照文件（它只认 6 列 TSV，不认数据库），所以本表的任何增删改
 * 都必须经过 {@code ConfigAdminService#rebuildDict()} 重新生成快照再 {@code engine.reload(...)}
 * 才算生效。直接改本表而不重建，等于改了账本没改出货单。</p>
 *
 * <p><b>{@code uk_word} 与逻辑删除打架，这是 T6.2 唯一需要提前想清楚的坑</b>：
 * 唯一键建在 {@code word} 上、不含 {@code deleted}，于是「删掉一个词再以同一个词加回来」
 * 会撞 1062，而不是插出一行新的。管理端的做法是先查含已删的行（{@code SensitiveWordMapper#findByWordAny}
 * 绕过 {@code @TableLogic}），命中软删行就把它救活，
 * 而不是把 {@code deleted} 置成 1 的那行永远留在库里挡路。</p>
 */
@Data
@TableName("sensitive_word")
public class SensitiveWord {

  /** match_type 取值：包含匹配（DFA 主路）。 */
  public static final String MATCH_CONTAINS = "contains";
  /** 正则（隐私泄露类：手机号/QQ/微信/身份证，需求 §18.3）。 */
  public static final String MATCH_REGEX = "regex";
  /** 整词匹配：引擎 V1 按 contains 处理，保留档位是为了不骗 DDL（见 Mapper#toSnapshotLine）。 */
  public static final String MATCH_WHOLE = "whole";

  /** status 取值：启用。 */
  public static final int STATUS_ENABLED = 1;
  /** 停用：灰词误报率过高时临时关掉，不删行（保留 hit_cnt 这条历史）。 */
  public static final int STATUS_DISABLED = 0;

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 sensitive_word_group.id，决定本行的 level/action/hit_scope。 */
  private Long groupId;

  /** 词条原文，最长 64。 */
  private String word;

  /** 变体归一化后的 MD5（全半角/大小写/空格折叠后），批量导入去重用。 */
  private String variantHash;

  /** contains / regex / whole。 */
  private String matchType;

  /** 累计命中次数（词库优化与误报分析，需求 §7.2 #16）。 */
  private Integer hitCnt;

  /** 1 启用 / 0 停用。 */
  private Integer status;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}