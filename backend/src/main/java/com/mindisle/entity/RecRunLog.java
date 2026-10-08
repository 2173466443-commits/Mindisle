package com.mindisle.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 推荐重算台账 rec_run_log（手册 §10.5 · §15 阶段 8 队列① · 答辩 D7 · sql/19 第 36 表）。
 *
 * <p><b>一行 = 一次重算尝试</b>，成功、失败、被上一轮占用而跳过，三种结局各占一行。
 * 跳过也记是这张表存在的理由之一：阶段 7 只有内存摘要时，「作业没跑」和「作业跑了但没写进库」
 * 在接口上分不出来，只能靠猜（{@code AdminRecController} 的类注释当年就把这条局限写白了）。</p>
 *
 * <p><b>没有 deleted 列</b>：日志表只增不删，口径与 {@code ai_call_log} 逐字相同。
 * 台账一旦允许软删，「这个月失败过几次」就成了一个可以被编辑的答案。</p>
 *
 * <p><b>行里没有任何 user.id</b>：所以它在 {@code PrivacyDomains} 里是
 * {@code Link.NONE + Action.KEEP}——注销清除不碰它，导出包也不带它。
 * 导出的是「这个人的数据」，而这里记的是「平台的作业记录」，两件事不能混进同一个包。</p>
 */
@Data
@TableName("rec_run_log")
public class RecRunLog {

  /** 触发口：定时轮（{@code RecommendJob#rebuild}）。 */
  public static final String TRIGGER_SCHEDULE = "schedule";
  /** 触发口：管理端「立即重算」按钮（{@code POST /api/admin/rec/rebuild}）。 */
  public static final String TRIGGER_MANUAL = "manual";

  /** 本轮写进了库。 */
  public static final String STATUS_SUCCESS = "SUCCESS";
  /** 本轮抛异常，上一批缓存原样留着（在线侧 TTL 到点自动退热度兜底）。 */
  public static final String STATUS_FAILED = "FAILED";
  /** 重入锁没抢到：上一轮还没结束，本轮直接放弃。 */
  public static final String STATUS_SKIPPED_BUSY = "SKIPPED_BUSY";

  @TableId(type = IdType.AUTO)
  private Long id;

  /** schedule / manual，取值必须是上面两个常量之一；DDL 侧是同一组 ENUM 值。 */
  private String triggerType;

  /** SUCCESS / FAILED / SKIPPED_BUSY。 */
  private String status;

  private LocalDateTime startedAt;

  /** SKIPPED_BUSY 时等于 {@code startedAt}：本轮确实什么都没做，别把它写成四舍五入后的 0。 */
  private LocalDateTime finishedAt;

  private Long durationMs;

  /** {@code Summary.mode}：emotion-on / emotion-off 或提前返回的原因短码；SKIPPED_BUSY 为 null。 */
  private String mode;

  /** 本轮覆盖的用户数，已被 rec-rebuild-user-limit 截断后的真值。 */
  private Integer userCnt;

  private Integer qualityRows;

  private Integer topicRows;

  /** 0 = 相似位只剩话题兜底与质量分榜（判据 D6）。 */
  private Integer similarityRows;

  /** 0 = 在线侧全体退热度兜底，个性化没生效（判据 D1）。 */
  private Integer resultRows;

  /** 通道占比重成的 JSON 串，见 {@code RecRunLogService#toJson}；DDL 侧 VARCHAR(500)。 */
  private String channelShare;

  /** 失败时的异常 toString，落库前截到 1000；堆栈的家是日志文件，不是这张表。 */
  private String errorText;

  private LocalDateTime createdAt;
}
