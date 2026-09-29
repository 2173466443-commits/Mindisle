package com.mindisle.recommend;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.config.MindisleProperties;

/**
 * 推荐重算的定时入口（任务 T7.14 · 手册 §10.1「离线侧」· 需求 §6.2 的 P95 前提）。
 *
 * <p><b>为什么是 fixedDelay 而不是 cron</b>：一轮重算的耗时取决于「活跃用户数 × 候选池大小」，
 * 现在库里是几百人几十毫秒，演示时灌一万条行为就可能是几十秒。cron 是「到点就开一轮」，
 * 上一轮没跑完就会叠两轮并发写同一批 {@code recommend_result} 行 —— 先删后插交叉执行，
 * 结果是 position 编号错乱（同一帖两行、position 断号）。fixedDelay 天然是「跑完再等 N 分钟」，
 * 加上下面那个 {@link AtomicBoolean}，双保险。这与 {@code PmDeliveryRetryJob} 同一个取舍。</p>
 *
 * <p><b>线程模型：这里绝对不加 {@code @Async}</b>。全站唯一的 {@code TaskScheduler} 由
 * {@code WebSocketConfig} 注册（手册 §8 记过一次：多候选 TaskScheduler 会让 {@code @Scheduled}
 * 在启动期解析失败，服务直接起不来）。fixedDelay 的等待是在调度线程里睡的，所以一轮长批次
 * 会挤住私信重投 —— 演示期可接受，且 {@code rec-rebuild-enabled} 就是为此留的关。</p>
 *
 * <p><b>失败为什么只 log 不外抛</b>：{@code @Scheduled} 方法抛出的异常会被 Spring 的
 * 错误处理器吞掉并停掉该任务的后续调度（Spring 文档明确写着默认行为），
 * 那等于「一次数据库抖动 → 推荐永久不再重算」，而在线侧只会在 TTL 到点后静默退化成热度榜，
 * 没人能从接口返回里看出推荐已经死了。所以这里自己 catch，并把失败摘要留在日志里。</p>
 */
@Component
public class RecommendJob {

  private static final Logger log = LoggerFactory.getLogger(RecommendJob.class);

  /** 缺省重算间隔 30 分钟，与手册 §10.1「每 30 分钟一批」逐字一致。 */
  static final long DEFAULT_INTERVAL_MILLIS = 1_800_000L;

  private final OfflineRecommendService recommendService;
  private final MindisleProperties properties;

  /** 防重入闸：{@link #rebuild()} 与 {@code POST /api/admin/rec/rebuild} 共用一把锁。 */
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** 最近一轮的摘要与完成时刻，供 {@code GET /api/admin/rec/status} 读（A2 大屏的「推荐是否活着」）。 */
  private volatile OfflineRecommendService.Summary lastSummary;
  private volatile LocalDateTime lastFinishedAt;
  private volatile String lastFailure;

  public RecommendJob(OfflineRecommendService recommendService, MindisleProperties properties) {
    this.recommendService = recommendService;
    this.properties = properties;
  }

  public OfflineRecommendService.Summary lastSummary() {
    return lastSummary;
  }

  public LocalDateTime lastFinishedAt() {
    return lastFinishedAt;
  }

  /** 最近一次失败的原因（成功时不清空：运维要看的是「上次为什么红」）。 */
  public String lastFailure() {
    return lastFailure;
  }

  public boolean isRunning() {
    return running.get();
  }

  /** 定时触发的那一轮。开关关掉时打一条 INFO 说明跳过，而不是沉默（手册 §8 的日志纪律）。 */
  @Scheduled(fixedDelayString = "${mindisle.schedule.rec-rebuild-fixed-delay-millis:1800000}")
  public void rebuild() {
    if (!properties.getSchedule().isRecRebuildEnabled()) {
      log.info("推荐重算已被配置关闭（mindisle.schedule.rec-rebuild-enabled=false），本轮跳过");
      return;
    }
    rebuildOnce();
  }

  /**
   * 跑一轮重算，返回该轮摘要；被占用或失败时返回 null。
   *
   * <p>单独抽出来是给管理端「立即重算」按钮用的（任务 T7.15）：演示与验收不可能等 30 分钟。
   * 它与定时入口共用同一把重入锁，所以点按钮不会和定时轮打架。</p>
   */
  public OfflineRecommendService.Summary rebuildOnce() {
    if (!running.compareAndSet(false, true)) {
      log.warn("推荐重算：上一轮还没结束，本轮跳过（若频繁出现这条日志，说明批次耗时已超过重算间隔）");
      return null;
    }
    long started = System.currentTimeMillis();
    try {
      int userLimit = Math.max(0, properties.getSchedule().getRecRebuildUserLimit());
      OfflineRecommendService.Summary summary = recommendService.rebuildAll(LocalDateTime.now(), userLimit);
      lastSummary = summary;
      lastFinishedAt = LocalDateTime.now();
      if (summary.resultRows() == 0 && summary.similarityRows() == 0) {
        log.warn("推荐重算：本轮零写入（mode={} 耗时{}ms），在线侧仍读上一批缓存，TTL 到点退热度兜底",
            summary.mode(), summary.calcMs());
      }
      return summary;
    } catch (RuntimeException e) {
      lastFailure = e.toString();
      log.error("推荐重算失败，保留上一批缓存（在线侧 TTL 到点自动退热度兜底）：{}", e.toString(), e);
      return null;
    } finally {
      running.set(false);
      log.debug("推荐重算入口收尾：本轮占用{}ms", System.currentTimeMillis() - started);
    }
  }
}