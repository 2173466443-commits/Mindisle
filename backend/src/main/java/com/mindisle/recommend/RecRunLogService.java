package com.mindisle.recommend;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mindisle.entity.RecRunLog;
import com.mindisle.mapper.RecRunLogMapper;
import com.mindisle.recommend.OfflineRecommendService.Summary;

/**
 * 推荐重算台账的写侧与读侧（手册 §10.5 · §15 阶段 8 队列① · 答辩 D7）。
 *
 * <p><b>为什么写失败绝不外抛</b>：{@code RecommendJob#rebuildOnce} 的收尾里调本类，
 * 而那一轮的重算结果已经拿到了。如果「台账插不进去」能把整次重算打成一个 HTTP 500，
 * 管理员点「立即重算」看到的就是失败，可缓存其实已经写好了——那是拿一张记账表
 * 去绑架真正的业务结果。所以 {@link #write} 只 {@code log.error}，
 * 代价写在手册里：台账可能缺一行，但缺的那一行会在那份日志里留下原话。</p>
 *
 * <p><b>为什么跳过也要记一行</b>：只记成功的台账回答不了现场唯一要紧的问题
 * 「这一轮为什么没数」。「被配置关了」「上一轮没结束」「真抛异常了」是三件事，
 * 在库里就该是三行长得不一样的记录。</p>
 *
 * <p><b>{@code channel_share} 在这里拼 JSON 而不是交给 Jackson</b>：值域是
 * {@code Map<String,Integer>}，键是本仓库自有的通道名（{@code RecConstants} 那几个常量），
 * 不含任何用户输入。手写一个排序拼接的编码器 = 零依赖、键序确定、单测能逐字钉住输出；
 * 换成 Jackson 反而要把「键序随版本漂移」这件事再解释一遍。</p>
 */
@Service
public class RecRunLogService {

  private static final Logger log = LoggerFactory.getLogger(RecRunLogService.class);

  /** error_text 列宽 VARCHAR(1000)。栈深不进库：堆栈的家是日志文件。 */
  static final int ERROR_MAX = 1000;

  /** channel_share 列宽 VARCHAR(500)。真值现量远小于它（四五个通道各一个两位数）。 */
  static final int CHANNEL_MAX = 500;

  /** 一次最多取多少轮：A9 的台账卡片只放得下几十行，200 是「查因时往回数一天」的上限。 */
  static final int RECENT_MAX = 200;
  static final int RECENT_DEFAULT = 50;

  private final RecRunLogMapper runMapper;

  public RecRunLogService(RecRunLogMapper runMapper) {
    this.runMapper = runMapper;
  }

  /** 本轮写进了库。summary 为 null 时按「零写入的成功」记账，而不是不记——没有台账行比一行 0 更难解释。 */
  public void recordSuccess(String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
      long durationMs, Summary summary) {
    RecRunLog row = base(trigger, RecRunLog.STATUS_SUCCESS, startedAt, finishedAt, durationMs);
    if (summary != null) {
      row.setMode(summary.mode());
      row.setUserCnt(summary.users());
      row.setQualityRows(summary.qualityRows());
      row.setTopicRows(summary.topicRows());
      row.setSimilarityRows(summary.similarityRows());
      row.setResultRows(summary.resultRows());
      row.setChannelShare(cut(toJson(summary.channelShare()), CHANNEL_MAX));
    }
    write(row);
  }

  /** 本轮抛异常。上一批缓存原样留着，所以「失败」不等于「在线侧也坏了」，这句话要能在台账里读出来。 */
  public void recordFailure(String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
      long durationMs, RuntimeException failure) {
    RecRunLog row = base(trigger, RecRunLog.STATUS_FAILED, startedAt, finishedAt, durationMs);
    row.setErrorText(cut(failure == null ? "unknown" : failure.toString(), ERROR_MAX));
    write(row);
  }

  /** 重入锁没抢到：本轮什么都没做，于是 started_at = finished_at、duration_ms = 0。 */
  public void recordSkippedBusy(String trigger, LocalDateTime at) {
    write(base(trigger, RecRunLog.STATUS_SKIPPED_BUSY, at, at, 0L));
  }

  /**
   * 台账读数（{@code GET /api/admin/rec/runs}）。
   *
   * <p>四个键都是给管理员的一句话：{@code counts} 是「最近这段跑了多少轮、红了几轮」，
   * {@code runs} 是按新到旧的那些行，{@code returned} 与 {@code limit} 说明「是不是被截了」。
   * 截断必须自己说：一张只显示最近 50 轮的台账如果不自报截断，
   * 「本季度只失败过一次」这种话就会被当作统计结论念出来。</p>
   */
  public Map<String, Object> ledger(int limit) {
    int cap = clampLimit(limit);
    List<RecRunLog> runs = runMapper.recent(cap);
    Map<String, Object> counts = new LinkedHashMap<>();
    long total = 0L;
    for (Map<String, Object> r : runMapper.countByStatus()) {
      Object status = r.get("status");
      Object cnt = r.get("cnt");
      long value = cnt instanceof Number number ? number.longValue() : 0L;
      counts.put(String.valueOf(status), value);
      total += value;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("limit", cap);
    out.put("returned", runs.size());
    out.put("truncated", total > runs.size());
    out.put("totalRuns", total);
    out.put("counts", counts);
    out.put("runs", runs);
    return out;
  }

  /** 全库轮数（截断判断的分母）。 */
  static int clampLimit(int limit) {
    if (limit <= 0) {
      return RECENT_DEFAULT;
    }
    return Math.min(limit, RECENT_MAX);
  }

  private static RecRunLog base(String trigger, String status, LocalDateTime startedAt,
      LocalDateTime finishedAt, long durationMs) {
    // status 在 DDL 侧是 ENUM 且 NOT NULL：写进非法值会被 MySQL 直接拒，所以在这里先挡一句，
    // 让「有人新加了一种结局却忘了同步 DDL」在单测里就红，而不是在现场插入失败后再去翻日志。
    if (!RecRunLog.STATUS_SUCCESS.equals(status) && !RecRunLog.STATUS_FAILED.equals(status)
        && !RecRunLog.STATUS_SKIPPED_BUSY.equals(status)) {
      throw new IllegalArgumentException("非法的重算结局：" + status);
    }
    RecRunLog row = new RecRunLog();
    row.setTriggerType(trigger == null ? RecRunLog.TRIGGER_SCHEDULE : trigger);
    row.setStatus(status);
    row.setStartedAt(startedAt);
    row.setFinishedAt(finishedAt);
    row.setDurationMs(durationMs);
    row.setUserCnt(0);
    row.setQualityRows(0);
    row.setTopicRows(0);
    row.setSimilarityRows(0);
    row.setResultRows(0);
    // 不写 createdAt：DDL 有 DEFAULT CURRENT_TIMESTAMP(3)，让库自己盖时间戳比 Java 传一个进去更难漂移。
    return row;
  }

  private void write(RecRunLog row) {
    try {
      runMapper.insert(row);
    } catch (RuntimeException e) {
      // 台账坏了不能把重算的结果一起带走，理由见类注释第 1 条。
      log.error("推荐重算台账写入失败（本轮重算结果不受影响）：trigger={} status={} startedAt={}",
          row.getTriggerType(), row.getStatus(), row.getStartedAt(), e);
    }
  }

  /** 键序固定（TreeMap 排序）的紧凑 JSON；值全是整数，所以不需要转义。 */
  static String toJson(Map<String, Integer> share) {
    if (share == null || share.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder(64);
    sb.append('{');
    boolean first = true;
    for (Map.Entry<String, Integer> e : new TreeMap<>(share).entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append('"').append(quoteKey(e.getKey())).append('"').append(':').append(e.getValue());
    }
    return sb.append('}').toString();
  }

  /** 通道名是本仓库自有的常量，出现引号或控制字符说明有人把外部输入塞进了 share 的键。 */
  private static String quoteKey(String key) {
    if (key == null) {
      return "";
    }
    return key.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
  }

  static String cut(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }
}
