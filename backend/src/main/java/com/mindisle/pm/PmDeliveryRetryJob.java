package com.mindisle.pm;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mindisle.config.MindisleProperties;
import com.mindisle.entity.PrivateMessage;
import com.mindisle.pm.dto.PmAckView;

/**
 * 送达重投作业（任务 T5.3 · 手册 §8.2 第 2 条「离线不丢消息」）。
 *
 * <p><b>它只补推，绝不补通知</b>：通知在 {@link PmRealtimeListener} 里已经按「一条私信一条红点」
 * 写过了。如果这里再调一次 {@code notifyPm}，用户离线十分钟就会收到十几个同样的红点——
 * {@code NotifyService#shouldSkip} 的同文案幂等也许能挡一部分，但那条判据是「未读且文案相同」，
 * 用户一边读一边被重投照样会刷出新行。<b>通知写一次、推送可以重复</b>是这一层的基本分工：
 * 推送是幂等的（同一个气泡画两遍前端按 id 去重），通知不是。</p>
 *
 * <p><b>为什么用 {@code fixedDelay} 而不是 cron</b>：cron 定的是「几点起跑」，
 * 上一轮如果因为库慢跑到下一分钟，两轮就会重叠扫描同一批 sent 行并各推一遍。
 * fixedDelay 是「上一轮结束之后再等这么久」，天然不重叠。</p>
 *
 * <p><b>占位符带默认值 {@code :5000}</b>：这不是防御性写法，而是必需的——
 * {@code @Scheduled} 的表达式在容器启动期解析，键不存在就直接 BeanCreationException 起不来。
 * 配置类里有这个字段、yml 里也有它，仍然保留默认值，是为了让「删掉 yml 那一行」
 * 变成一个可观察的性能变化而不是一个开不了机的故障。</p>
 */
@Component
public class PmDeliveryRetryJob {

  private static final Logger log = LoggerFactory.getLogger(PmDeliveryRetryJob.class);

  private final PmService pmService;
  private final PmStore store;
  private final PmPushGateway gateway;
  private final MindisleProperties properties;

  public PmDeliveryRetryJob(PmService pmService, PmStore store, PmPushGateway gateway,
      MindisleProperties properties) {
    this.pmService = pmService;
    this.store = store;
    this.gateway = gateway;
    this.properties = properties;
  }

  /**
   * 一轮重投。返回条数只写日志，不返回给任何人——没有接口需要它，
   * 加一个「上次重投了几条」的读数就得再加一张表和一次写入。
   *
   * <p>{@code retry-grace-seconds}（默认 3）是这一句的判据核心：刚发出去 3 秒内的行
   * 还在实时链路上，不算超时。没有宽限期的话每一封私信都会在下一个 5 秒周期被再推一遍，
   * 「重投」就变成了「双倍推送」。</p>
   */
  @Scheduled(fixedDelayString = "${mindisle.pm.retry-interval-millis:5000}")
  public void retryUndelivered() {
    LocalDateTime now = LocalDateTime.now();
    List<PrivateMessage> candidates = pmService.undeliveredCandidates(now);
    if (candidates.isEmpty()) {
      return;
    }
    int pushed = 0;
    for (PrivateMessage row : candidates) {
      // 先确认人在线再推：sendToUser 内部已经查过，这里只是少打一行无意义的日志。
      if (!gateway.isOnline(row.getToUserId() == null ? 0L : row.getToUserId())) {
        continue;
      }
      if (gateway.sendToUser(row.getToUserId(), PmPushGateway.DEST_PRIVATE,
          pmService.viewForReceiver(row))) {
        // 只有真的把这一行从 sent 翻成 delivered 才给发信方回执：并发下
        // PmRealtimeListener 可能已经抢先翻过（同一条 UPDATE ... WHERE status=sent 返回 0 行），
        // 补一份重复的 delivered 会让发信方气泡上的「已送达」动画播两遍。
        if (store.markDelivered(row.getId()) > 0) {
          // 补推成功也要告诉发信方「他此刻真的收到了」——这是第 5 阶段实时链路的最后一公里：
          // 没有这一帧，用户离线十分钟再上线，发信方的气泡会永远停在「已发送」，
          // 而那封私信其实已经在对方屏幕上了。回执口径与 PmRealtimeListener 完全一致（同一形状、同一目的地）。
          gateway.sendToUser(row.getFromUserId(), PmPushGateway.DEST_ACK, new PmAckView(
              "delivered", row.getClientMsgId(), row.getId(), row.getToUserId(),
              null, null, LocalDateTime.now(), null, null));
        }
        pushed++;
      }
    }
    log.info("私信重投一轮 候选={} 送达={} 宽限={}s 批量上限={}", candidates.size(), pushed,
        properties.getPm().getRetryGraceSeconds(), properties.getPm().getRetryBatchLimit());
  }
}
