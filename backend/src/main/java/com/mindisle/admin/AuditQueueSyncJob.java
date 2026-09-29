package com.mindisle.admin;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.config.MindisleProperties;

/**
 * 审核队列的定时同步（任务 T6.6 · 需求 FR7.4 FR7.5）。
 *
 * <p>三件事一轮做完：历史命中补进队列、无 key 时的图片帖抽审、超时未裁决的任务放回队列。
 * 都放在同一个 {@code @Scheduled} 里而不是拆三个作业，是因为它们共用同一个判据时刻
 * {@code now}——拆开跑会出现「A 用 09:00:00 建任务、B 用 09:00:00 判超时」的自欺。</p>
 *
 * <p>线程模型：项目里唯一的 {@code TaskScheduler} 由 {@code WebSocketConfig} 注册，
 * 本作业与周报、保留期作业、私信重投共用它。所以<b>不要再在这里加 {@code @Async}</b>：
 * 多候选 TaskScheduler 会让 {@code @Scheduled} 的解析直接失败（手册 §8 已记过一次）。</p>
 */
@Component
public class AuditQueueSyncJob {

  private static final Logger log = LoggerFactory.getLogger(AuditQueueSyncJob.class);

  /** 单轮补建任务的上限，配置只能把它调小（与 {@code WeeklyReportJob.MAX_BATCH} 同一族纪律）。 */
  static final int MAX_BATCH = 500;

  private final AuditQueueService queueService;
  private final MindisleProperties properties;

  public AuditQueueSyncJob(AuditQueueService queueService, MindisleProperties properties) {
    this.queueService = queueService;
    this.properties = properties;
  }

  /** 一轮同步：文本命中 + 图片抽审 + 超时回队。异常不外抛，作业失败下一轮还会再来。 */
  @Scheduled(cron = "${mindisle.schedule.audit-sync-cron:0 * * * * ?}")
  public void sync() {
    MindisleProperties.Schedule schedule = properties.getSchedule();
    if (!schedule.isAuditSyncEnabled()) {
      log.info("审核队列同步已被配置关闭（mindisle.schedule.audit-sync-enabled=false），本轮跳过");
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    int limit = Math.min(Math.max(schedule.getAuditSyncBatchLimit(), 1), MAX_BATCH);
    try {
      AuditQueueService.SyncStat text = queueService.syncPostsToTasks(now);
      AuditQueueService.SyncStat image = queueService.syncImagePostsToTasks(now, limit);
      int released = queueService.releaseTimedOut(now);
      if (text.created() + image.created() + released > 0) {
        log.info("审核队列同步：文本 扫{}建{}重{}｜图片 扫{}建{}重{}｜超时回队{}",
            text.scanned(), text.created(), text.duplicated(),
            image.scanned(), image.created(), image.duplicated(), released);
      }
    } catch (RuntimeException e) {
      // 不让一次异常把这一轮整段带走：文本与图片是两条独立通道，能救一条是一条。
      log.error("审核队列同步失败，本轮放弃（下一轮会重试）：{}", e.toString(), e);
    }
  }
}